/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Studio FlowCode {@code _coerce_value} subset (L2 §4.3.2).
 *
 * @since 2026-08-17
 */
public final class TypeCoercer {
    private TypeCoercer() {}

    /**
     * Coerce a map of values using an optional per-field schema.
     *
     * @param inputs inputs
     * @param schema schema
     * @return coerced map
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> coerceMap(Map<String, Object> inputs, Map<String, Object> schema) {
        if (inputs == null) {
            return Map.of();
        }
        if (schema == null || schema.isEmpty()) {
            return new LinkedHashMap<>(inputs);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : inputs.entrySet()) {
            Object sch = schema.get(e.getKey());
            out.put(e.getKey(), coerce(e.getValue(), typeOf(sch), sch));
        }
        return out;
    }

    private static String typeOf(Object sch) {
        if (sch instanceof Map<?, ?> m) {
            Object t = m.get("type");
            return t == null ? "" : String.valueOf(t);
        }
        return sch == null ? "" : String.valueOf(sch);
    }

    /**
     * Coerce a single value.
     *
     * @param value value
     * @param type type
     * @param schema schema
     * @return coerced value
     */
    @SuppressWarnings("unchecked")
    public static Object coerce(Object value, String type, Object schema) {
        if (type == null || type.isEmpty() || value == null) {
            return value;
        }
        return switch (type) {
            case "string" -> String.valueOf(value);
            case "integer" -> value instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(value));
            case "number" -> value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
            case "boolean" -> value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
            case "object" -> value instanceof Map<?, ?> ? value : Map.of("value", value);
            case "array" -> {
                if (value instanceof List<?> list) {
                    yield new ArrayList<>(list);
                }
                yield List.of(value);
            }
            default -> value;
        };
    }
}
