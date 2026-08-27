/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowinput;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python {@code FlowInputUtils}.
 *
 * @since 2026-08-26
 */

public final class FlowInputUtils {
    /**
     * USER_FIELDS.
     * @since 0.1.0
     */
    public static final String USER_FIELDS = "userFields";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private static final Map<String, Object> TYPE_DEFAULTS = Map.of(
            "string",
            "",
            "number",
            0,
            "boolean",
            false,
            "array",
            List.of(),
            "object",
            Map.of());

    private FlowInputUtils() {}

    /**
     * Python {@code parse_user_response}.
     *
     * @param userResponse response
     * @return field map
     */

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
        try {
            Object parsed = MAPPER.readValue(responseStr, Object.class);
            if (parsed instanceof Map<?, ?> pm) {
                Map<String, Object> out = new LinkedHashMap<>();
                pm.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (JsonProcessingException ignored) {
            // fall through to line parsing
        }
        for (String line : responseStr.split("\n")) {
            if (line.contains(":")) {
                String[] kv = line.split(":", 2);
                values.put(kv[0].trim(), kv[1].trim());
            }
        }
        return values;
    }

    /**
     * Python {@code build_inputs_message}.
     *
     * @param config node config
     * @return JSON string
     */

    public static String buildInputsMessage(Map<String, Object> config) {
        List<Map<String, Object>> inputsNeed = inputDefs(config);
        List<Map<String, Object>> converted = new ArrayList<>();
        for (Map<String, Object> item : inputsNeed) {
            converted.add(convertFormat(item));
        }
        try {
            return MAPPER.writeValueAsString(Map.of("inputs", converted));
        } catch (JsonProcessingException e) {
            // fallback compact
            StringBuilder sb = new StringBuilder("{\"inputs\":[");
            for (int i = 0; i < converted.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(mapToJsonish(converted.get(i)));
            }
            sb.append("]}");
            return sb.toString();
        }
    }

    /**
     * Python {@code validate_inputs} (mutates inputs with coerced values).
     *
     * @param inputs inputs
     * @param config config
     */

    public static void validateInputs(Map<String, Object> inputs, Map<String, Object> config) {
        Map<String, Object> values = inputs == null ? new LinkedHashMap<>() : inputs;
        for (Map<String, Object> def : inputDefs(config)) {
            String id = fieldId(def);
            if (id == null) {
                continue;
            }
            boolean required = isRequired(def);
            String flowType = String.valueOf(def.getOrDefault("type", "string")).toLowerCase();
            if (required && !values.containsKey(id)) {
                throw new IllegalArgumentException("Required field '" + id + "' is missing");
            }
            if (!values.containsKey(id)) {
                continue;
            }
            Object value = values.get(id);
            if (value == null || "".equals(value)) {
                if (required) {
                    throw new IllegalArgumentException("Required field '" + id + "' cannot be empty");
                }
                continue;
            }
            try {
                values.put(id, coerce(id, value, flowType));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "Field '" + id + "' has invalid type: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Python {@code fill_inputs}.
     *
     * <p>When a configured field is absent from {@code values}, keeps any existing value already on
     * {@code inputs} (Java NestedWorkflow resume may put the typed field into {@code response} as a
     * plain string via InteractiveInput — then {@link #parseUserResponse} yields an empty map).
     *
     * @param inputs base (mutated)
     * @param values user values
     * @param config config
     * @return inputs
     */

    public static Map<String, Object> fillInputs(
            Map<String, Object> inputs, Map<String, Object> values, Map<String, Object> config) {
        Map<String, Object> out = inputs == null ? new LinkedHashMap<>() : inputs;
        Map<String, Object> vals = values == null ? Map.of() : values;
        for (Map<String, Object> def : inputDefs(config)) {
            String id = fieldId(def);
            if (id == null) {
                continue;
            }
            boolean required = isRequired(def);
            String flowType = String.valueOf(def.getOrDefault("type", "string")).toLowerCase();
            Object inputValue = vals.containsKey(id) ? vals.get(id) : out.get(id);
            if (!required && isPythonFalsy(inputValue)) {
                inputValue = TYPE_DEFAULTS.getOrDefault(flowType, null);
            }
            out.put(id, inputValue);
        }
        return out;
    }

    static Map<String, Object> convertFormat(Map<String, Object> argConf) {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : argConf.entrySet()) {
            String key = "id".equals(e.getKey()) ? "name" : e.getKey();
            converted.put(key, e.getValue());
        }
        return converted;
    }

    @SuppressWarnings("unchecked")
    static Object coerce(String id, Object value, String type) {
        try {
            return switch (type) {
                case "string" -> value instanceof String ? value : String.valueOf(value);
                case "number" -> {
                    if (value instanceof Number n) {
                        yield n;
                    }
                    yield Double.parseDouble(String.valueOf(value));
                }
                case "boolean" -> {
                    if (value instanceof Boolean b) {
                        yield b;
                    }
                    if (value instanceof String s) {
                        String lower = s.toLowerCase();
                        yield "true".equals(lower) || "1".equals(lower) || "yes".equals(lower);
                    }
                    yield !isPythonFalsy(value);
                }
                case "array" -> {
                    if (value instanceof List<?> l) {
                        yield l;
                    }
                    if (value instanceof String s) {
                        yield MAPPER.readValue(s, List.class);
                    }
                    throw new TypeException(
                            "Expected list for field '"
                                    + id
                                    + "', got "
                                    + value.getClass().getSimpleName());
                }
                case "object" -> {
                    if (value instanceof Map<?, ?> m) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
                        yield copy;
                    }
                    if (value instanceof String s) {
                        yield MAPPER.readValue(s, MAP_TYPE);
                    }
                    throw new TypeException(
                            "Expected dict for field '"
                                    + id
                                    + "', got "
                                    + value.getClass().getSimpleName());
                }
                default -> value;
            };
        } catch (TypeException e) {
            throw e;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> inputDefs(Map<String, Object> config) {
        if (config == null) {
            return List.of();
        }
        Object uf = config.get(USER_FIELDS);
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

    /**
     * fieldId.
     *
     * @param def def
     * @return result
     * @since 0.1.0
     */

    public static String fieldId(Map<String, Object> def) {
        Object id = def.get("id");
        if (id == null) {
            id = def.get("name");
        }
        if (id == null) {
            id = def.get("fieldName");
        }
        return id == null ? null : String.valueOf(id);
    }

    private static boolean isRequired(Map<String, Object> def) {
        return Boolean.TRUE.equals(def.get("required"))
                || "true".equalsIgnoreCase(String.valueOf(def.get("required")));
    }

    /**
     * Python truthiness for fill_inputs empty check.
     *
     * @param value value
     * @return result
     * @since 0.1.0
     */
    private static boolean isPythonFalsy(Object value) {
        if (value == null) {
        return true;
    }
        if (value instanceof Boolean b) {
            return !b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() == 0.0;
        }
        if (value instanceof CharSequence cs) {
            return cs.isEmpty();
        }
        if (value instanceof List<?> l) {
            return l.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return m.isEmpty();
        }
        return false;
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

    private static final class TypeException extends RuntimeException {
        TypeException(String message) {
            super(message);
        }
    }
}
