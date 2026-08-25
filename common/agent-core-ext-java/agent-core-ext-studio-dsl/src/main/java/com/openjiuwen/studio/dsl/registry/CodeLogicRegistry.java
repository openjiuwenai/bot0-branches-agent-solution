/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.registry;

import com.openjiuwen.studio.dsl.contract.CodeLogic;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit registration of Java {@link CodeLogic} implementations (no ServiceLoader).
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
     * find.
     *
     * @param name name
     * @return result
     */
    public Optional<CodeLogic> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
