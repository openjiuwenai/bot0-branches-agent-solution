/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.studio.dsl.contract.AgentInvoker;
import com.openjiuwen.studio.dsl.contract.AgentRegistry;

import java.util.Map;

/**
 * DefaultAgentInvoker for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class DefaultAgentInvoker implements AgentInvoker {
    private final AgentRegistry registry;

    /**
     * DefaultAgentInvoker.
     *
     * @param registry registry
     */
    public DefaultAgentInvoker(AgentRegistry registry) {
        this.registry = registry;
    }

    /**
     * invoke.
     *
     * @param agentId agentId
     * @param inputs inputs
     * @return result
     * @throws Exception when the call fails
     */
    @Override
    public Map<String, Object> invoke(String agentId, Map<String, Object> inputs) throws Exception {
        return registry
                .find(agentId)
                .orElseThrow(() -> new IllegalStateException("agent not registered: " + agentId))
                .apply(inputs == null ? Map.of() : inputs);
    }
}
