/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-Task Agent object lifecycle (DFX-002).
 *
 * <p>Each call to {@link #acquire(String)} creates a new Agent instance via
 * the {@link AgentFactory}. Shared resources (LLM client, Redis, etc.) are
 * injected at construction time and passed to each new instance.
 *
 * @since 0.1.0
 */
public class AgentInstanceManager {
    private final AgentFactory factory;

    private final ConcurrentHashMap<String, Object> activeAgents = new ConcurrentHashMap<>();

    /**
     * Creates a manager with the given factory.
     *
     * @param factory the Agent factory
     */
    public AgentInstanceManager(AgentFactory factory) {
        this.factory = factory;
    }

    /**
     * Acquire a new Agent instance for the given conversation.
     *
     * <p>Each call invokes {@link AgentFactory#create()} to produce a fresh
     * instance. The instance is tracked internally until released. Concurrent
     * acquire for the same conversationId is rejected.
     *
     * <p>Issue #157: the conversation slot is reserved atomically with a
     * short-lived placeholder <em>before</em> the expensive
     * {@code factory.create()} runs. A concurrent acquire for a conversation
     * whose agent is still being created fails fast with
     * {@link ConversationBusyException} — it neither waits for the ongoing
     * creation nor performs a wasted {@code create()} + {@code destroy()}
     * round-trip. If {@code create()} fails or returns null, the placeholder
     * is removed so the conversation stays acquirable.
     *
     * <p>The reservation also avoids {@code computeIfAbsent}-style long
     * computations under the map's bin lock: every map operation here
     * ({@code putIfAbsent}, {@code put}, {@code remove}) is short, so a slow
     * creation never blocks unrelated conversations' operations.
     *
     * @param conversationId conversation identifier
     * @return a new Agent object
     * @throws ConversationBusyException if the conversation already has an
     *         active agent (or one being created)
     */
    public Object acquire(String conversationId) {
        Object placeholder = new Object();
        if (activeAgents.putIfAbsent(conversationId, placeholder) != null) {
            throw new ConversationBusyException(
                    "Conversation already has an active or initializing agent: " + conversationId);
        }
        try {
            Object agent = factory.create();
            if (agent == null) {
                throw new IllegalStateException(
                        "AgentFactory.create() returned null for conversation: " + conversationId);
            }
            activeAgents.put(conversationId, agent);
            return agent;
        } catch (RuntimeException | Error failure) {
            activeAgents.remove(conversationId, placeholder);
            throw failure;
        }
    }

    /**
     * Release an Agent instance and its resources.
     *
     * <p>Idempotent (issue #156): the entry is removed with
     * {@code remove(key, agent)} — a value-equality check — so only the caller
     * presenting the currently tracked agent destroys it. A second release of
     * the same agent, or a release racing with a newer acquire that replaced
     * the entry, finds the entry gone (or pointing at another instance) and
     * does nothing, preventing a double {@code destroy()} of the same instance
     * or destruction of a successor's agent.
     *
     * @param conversationId conversation identifier
     * @param agent the Agent object to release
     */
    public void release(String conversationId, Object agent) {
        if (activeAgents.remove(conversationId, agent) && agent != null) {
            factory.destroy(agent);
        }
    }

    /**
     * Returns the active agent for a conversation without acquiring a new one.
     *
     * <p>While an agent is being created for the conversation this returns the
     * internal reservation placeholder (never an usable agent); callers must
     * treat any non-null value merely as "conversation is busy".
     *
     * @param conversationId conversation identifier
     * @return the active agent (or reservation placeholder), or null if none exists
     */
    public Object get(String conversationId) {
        return activeAgents.get(conversationId);
    }
}
