/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowapi;

/**
 * Python {@code transform_type} for FlowApi inputs.
 *
 * @since 2026-08-26
 */

final class FlowApiTypeTransform {
    private FlowApiTypeTransform() {}

    static Object transform(String nodeId, Object value, String expectedType, String key) {
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
            throw FlowApiErrors.of(nodeId, FlowApiStatusCode.WORKFLOW_API_PARAMS_CHECK_ERROR, "");
        }
    }
}
