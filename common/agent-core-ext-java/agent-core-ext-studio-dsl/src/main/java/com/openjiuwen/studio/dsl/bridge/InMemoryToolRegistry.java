/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.studio.dsl.spi.ToolRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemoryToolRegistry for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class InMemoryToolRegistry implements ToolRegistry {
    private final Map<String, Tool> byId = new ConcurrentHashMap<>();

    /**
     * register.
     *
     * @param apiId apiId
     * @param tool tool
     */
    public void register(String apiId, Tool tool) {
        byId.put(apiId, tool);
    }

    /**
     * find.
     *
     * @param apiId apiId
     * @return result
     */
    @Override
    public Optional<Tool> find(String apiId) {
        return Optional.ofNullable(byId.get(apiId));
    }
}
