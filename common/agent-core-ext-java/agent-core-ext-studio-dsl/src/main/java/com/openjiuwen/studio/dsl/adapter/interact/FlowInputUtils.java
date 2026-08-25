/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FlowInput helpers (Python FlowInputUtils).
 *
 * @since 2026-08-25
 */
public final class FlowInputUtils {
    private FlowInputUtils() {}

    public static String buildInputsMessage(Map<String, Object> config) {
        List<Map<String, Object>> defs = inputDefs(config);
        StringBuilder sb = new StringBuilder("{\"inputs\":[");
        for (int i = 0; i < defs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            Map<String, Object> def = new LinkedHashMap<>(defs.get(i));
            if (def.containsKey("id") && !def.containsKey("name")) {
                def.put("name", def.get("id"));
            }
            if (def.containsKey("type") && !def.containsKey("actualType")) {
                def.put("actualType", def.get("type"));
            }
            sb.append(mapToJsonish(def));
        }
        sb.append("]}");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseUserResponse(Object userResponse) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (userResponse == null) {
            return values;
        }
        if (userResponse instanceof Map<?, ?> m) {
            m.forEach((k, v) -> values.put(String.valueOf(k), v));
            return values;
        }
        String responseStr = String.valueOf(userResponse).trim();
        if (responseStr.isEmpty()) {
            return values;
        }
        if (responseStr.startsWith("{") && responseStr.endsWith("}")) {
            String inner = responseStr.substring(1, responseStr.length() - 1);
            for (String part : inner.split(",")) {
                int colon = part.indexOf(':');
                if (colon > 0) {
                    String k = part.substring(0, colon).trim().replace("\"", "");
                    String v = part.substring(colon + 1).trim().replace("\"", "");
                    values.put(k, v);
                }
            }
            return values;
        }
        for (String line : responseStr.split("\n")) {
            if (line.contains(":")) {
                String[] kv = line.split(":", 2);
                values.put(kv[0].trim(), kv[1].trim());
            }
        }
        if (values.isEmpty()) {
            values.put("input", responseStr);
        }
        return values;
    }

    public static void validateInputs(Map<String, Object> inputs, Map<String, Object> config) {
        Map<String, Object> values = inputs == null ? Map.of() : inputs;
        for (Map<String, Object> def : inputDefs(config)) {
            String id = fieldId(def);
            if (id == null) {
                continue;
            }
            boolean required =
                    Boolean.TRUE.equals(def.get("required"))
                            || "true".equalsIgnoreCase(String.valueOf(def.get("required")));
            Object value = values.get(id);
            if (required && (value == null || "".equals(value))) {
                throw new IllegalArgumentException("Required field '" + id + "' is missing");
            }
            if (value != null && !"".equals(value)) {
                coerce(id, value, String.valueOf(def.getOrDefault("type", "string")).toLowerCase());
            }
        }
    }

    static Object coerce(String id, Object value, String type) {
        try {
            return switch (type) {
                case "integer" -> value instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(value));
                case "number" -> value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
                case "boolean" -> {
                    if (value instanceof Boolean b) {
                        yield b;
                    }
                    yield Boolean.parseBoolean(String.valueOf(value));
                }
                default -> value;
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Field '" + id + "' type coerce failed: " + type, e);
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> inputDefs(Map<String, Object> config) {
        if (config == null) {
            return List.of();
        }
        Object uf = config.get("userFields");
        Object inputsCfg = null;
        if (uf instanceof Map<?, ?> m) {
            inputsCfg = m.get("inputs");
        }
        if (inputsCfg == null) {
            inputsCfg = config.get("inputs");
        }
        if (!(inputsCfg instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> d = new LinkedHashMap<>();
                m.forEach((k, v) -> d.put(String.valueOf(k), v));
                out.add(d);
            }
        }
        return out;
    }

    static String fieldId(Map<String, Object> def) {
        Object id = def.get("id");
        if (id == null) {
            id = def.get("name");
        }
        if (id == null) {
            id = def.get("fieldName");
        }
        return id == null ? null : String.valueOf(id);
    }

    private static String mapToJsonish(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v == null) {
                sb.append("null");
            } else {
                sb.append('"').append(String.valueOf(v).replace("\"", "\\\"")).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }
}
