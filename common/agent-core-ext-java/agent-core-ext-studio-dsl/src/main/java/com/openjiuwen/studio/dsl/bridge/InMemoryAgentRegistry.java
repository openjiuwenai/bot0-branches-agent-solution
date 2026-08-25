/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.studio.dsl.spi.AgentRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * InMemoryAgentRegistry for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class InMemoryAgentRegistry implements AgentRegistry {
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> byId = new ConcurrentHashMap<>();

    /**
     * register.
     *
     * @param agentId agentId
     * @param fn fn
     */
    public void register(String agentId, Function<Map<String, Object>, Map<String, Object>> fn) {
        byId.put(agentId, fn);
    }

    /**
     * find.
     *
     * @param agentId agentId
     * @return result
     */
    @Override
    public Optional<Function<Map<String, Object>, Map<String, Object>>> find(String agentId) {
        return Optional.ofNullable(byId.get(agentId));
    }
}
