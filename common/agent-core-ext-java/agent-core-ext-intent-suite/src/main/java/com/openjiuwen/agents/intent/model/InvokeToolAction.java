/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request to execute an already installed DeepAgent tool.
 *
 * @param toolName target tool name
 * @param arguments complete target tool arguments
 *
 * @since 0.1.0
 */
public record InvokeToolAction(String toolName, Map<String, Object> arguments) implements IntentAction {
    /**
     * Creates an immutable tool action.
     */
    public InvokeToolAction {
        arguments = arguments == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(arguments));
    }
}
