/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.registry;

import com.openjiuwen.studio.dsl.spi.CodeLogic;

import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CodeLogicRegistry for Studio DSL node-type extension (FEAT-031).
 *
 * @since 2026-08-17
 */
public final class CodeLogicRegistry {
    private final Map<String, CodeLogic> byName = new ConcurrentHashMap<>();

    /**
     * register.
     *
     * @param logic logic
     */
    public void register(CodeLogic logic) {
        byName.put(logic.name(), logic);
    }

    /**
     * loadServiceLoader.
     */
    public void loadServiceLoader() {
        for (CodeLogic logic : ServiceLoader.load(CodeLogic.class)) {
            register(logic);
        }
    }

    /**
     * find.
     *
     * @param name name
     * @return result
     */
    public Optional<CodeLogic> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
