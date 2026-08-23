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
     * <p>Because {@link AgentFactory#create()} is expensive (serialized by the
     * factory's creation lock), a conversation that already has an active agent
     * is rejected <em>before</em> any instance is created. The
     * {@code putIfAbsent} re-check after creation guards the race window
     * between the pre-check and the insertion; a loser of that window has its
     * freshly created instance destroyed.
     *
     * @param conversationId conversation identifier
     * @return a new Agent object
     * @throws ConversationBusyException if the conversation already has an active agent
     */
    public Object acquire(String conversationId) {
        if (activeAgents.containsKey(conversationId)) {
            throw new ConversationBusyException("Conversation already has an active agent: " + conversationId);
        }
        Object agent = factory.create();
        if (activeAgents.putIfAbsent(conversationId, agent) != null) {
            factory.destroy(agent);
            throw new ConversationBusyException("Conversation already has an active agent: " + conversationId);
        }
        return agent;
    }

    /**
     * Release an Agent instance and its resources.
     *
     * @param conversationId conversation identifier
     * @param agent the Agent object to release
     */
    public void release(String conversationId, Object agent) {
        activeAgents.remove(conversationId);
        if (agent != null) {
            factory.destroy(agent);
        }
    }

    /**
     * Returns the active agent for a conversation without acquiring a new one.
     *
     * @param conversationId conversation identifier
     * @return the active agent, or null if none exists
     */
    public Object get(String conversationId) {
        return activeAgents.get(conversationId);
    }
}
