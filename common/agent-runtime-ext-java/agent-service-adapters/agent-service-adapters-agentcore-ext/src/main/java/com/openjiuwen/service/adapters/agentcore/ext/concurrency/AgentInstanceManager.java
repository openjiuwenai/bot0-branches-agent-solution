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
     * instance. The instance is tracked internally until released.
     *
     * @param conversationId conversation identifier
     * @return a new Agent object
     */
    public Object acquire(String conversationId) {
        Object agent = factory.create();
        activeAgents.put(conversationId, agent);
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
}
