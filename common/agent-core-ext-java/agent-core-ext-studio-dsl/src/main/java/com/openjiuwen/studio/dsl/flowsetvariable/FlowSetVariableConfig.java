/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowsetvariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code LoopSetVariable} ctor: {@code variable_mapping} + {@code operator_mapping}.
 *
 * <p>Also accepts Studio IR {@code settings} list ({@code left}/{@code right} value maps).
 *
 * @since 2026-08-26
 */
public final class FlowSetVariableConfig {
    private final Map<String, Object> variableMapping;
    private final Map<String, String> operatorMapping;

    FlowSetVariableConfig(Map<String, Object> variableMapping, Map<String, String> operatorMapping) {
        this.variableMapping = variableMapping == null ? Map.of() : Map.copyOf(variableMapping);
        this.operatorMapping = operatorMapping == null ? Map.of() : Map.copyOf(operatorMapping);
    }

    @SuppressWarnings("unchecked")
    public static FlowSetVariableConfig fromNodeConfigs(Map<String, Object> configs) {
        Map<String, Object> c = configs == null ? Map.of() : configs;
        Map<String, String> operators = new LinkedHashMap<>();
        Object rawOps = c.getOrDefault("operatorMapping", c.get("operator_mapping"));
        if (rawOps instanceof Map<?, ?> m) {
            m.forEach((k, v) -> operators.put(String.valueOf(k), String.valueOf(v)));
        }

        Map<String, Object> mapping = new LinkedHashMap<>();
        Object settings = c.get("settings");
        if (settings instanceof List<?> list && !list.isEmpty()) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> row)) {
                    continue;
                }
                String left = extractValue(row.get("left")).orElse(null);
                if (left == null || left.isBlank()) {
                    continue;
                }
                mapping.put(left, extractRaw(row.get("right")));
                // settings rows may key operators by left map object string — also try left value
                String op = operators.getOrDefault(String.valueOf(row.get("left")), operators.getOrDefault(left, ""));
                if (!op.isBlank()) {
                    operators.putIfAbsent(left, op);
                }
            }
        } else {
            Object rawMap = c.getOrDefault("variableMapping", c.get("variable_mapping"));
            if (rawMap instanceof Map<?, ?> m) {
                m.forEach((k, v) -> mapping.put(String.valueOf(k), v));
            }
        }
        return new FlowSetVariableConfig(mapping, operators);
    }

    public Map<String, Object> variableMapping() {
        return variableMapping;
    }

    public Map<String, String> operatorMapping() {
        return operatorMapping;
    }

    public boolean isEmpty() {
        return variableMapping.isEmpty();
    }

    private static java.util.Optional<String> extractValue(Object side) {
        if (side instanceof Map<?, ?> m) {
            Object v = m.get("value");
            return v == null ? java.util.Optional.empty() : java.util.Optional.of(String.valueOf(v));
        }
        return side == null ? java.util.Optional.empty() : java.util.Optional.of(String.valueOf(side));
    }

    private static Object extractRaw(Object side) {
        if (side instanceof Map<?, ?> m) {
            return m.get("value");
        }
        return side;
    }
}
