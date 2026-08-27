/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.contract;

import com.openjiuwen.core.foundation.tool.Tool;

import java.util.Optional;

/**
 * Empty {@link ToolRegistry} — host injects a real registry when plugin apiId lookup is needed.
 *
 * @since 2026-08-26
 */

public final class EmptyToolRegistry implements ToolRegistry {

    /**
     * INSTANCE.
     *
     * @since 0.1.0
     *
     * @return result
     */

    public static final EmptyToolRegistry INSTANCE = new EmptyToolRegistry();

    private EmptyToolRegistry() {}

    /**
     * find.
     *
     * @param apiId apiId
     * @return result
     * @since 0.1.0
     */

    @Override
    public Optional<Tool> find(String apiId) {
        return Optional.empty();
    }
}
