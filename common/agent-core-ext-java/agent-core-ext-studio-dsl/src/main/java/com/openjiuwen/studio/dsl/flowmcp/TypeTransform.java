/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowmcp;

/**
 * Type coercion for MCP argument values — 1:1 with Python {@code transform_type}.
 *
 * @since 2026-08-26
 */

final class TypeTransform {
    private TypeTransform() {}

    static Object transform(Object value, String expectedType, String key) {
        if (expectedType == null || expectedType.isBlank()) {
        return value;
    }
        String t = expectedType.toLowerCase();
        try {
            return switch (t) {
                case "string" -> String.valueOf(value);
                case "boolean" -> {
                    if (value instanceof Boolean b) {
                        yield b;
                    }
                    yield Boolean.parseBoolean(String.valueOf(value));
                }
                case "integer" -> {
                    if (value instanceof Number n) {
                        yield n.intValue();
                    }
                    yield Integer.parseInt(String.valueOf(value));
                }
                case "number" -> {
                    if (value instanceof Number n) {
                        yield n.doubleValue();
                    }
                    yield Double.parseDouble(String.valueOf(value));
                }
                default -> value;
            };
        } catch (RuntimeException e) {
            throw FlowMcpErrors.of(
                    FlowMcpStatusCode.WORKFLOW_MCP_PARAM_TYPE_ERROR,
                    java.util.Map.of("param", key, "type", expectedType));
        }
    }
}
