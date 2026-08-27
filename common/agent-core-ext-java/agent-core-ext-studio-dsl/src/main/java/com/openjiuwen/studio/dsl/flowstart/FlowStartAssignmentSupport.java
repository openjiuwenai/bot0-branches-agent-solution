/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowstart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Helpers for Studio Start preDefinedFields / assignment parsing (Python start.py).
 *
 * @since 2026-08-25
 */

public final class FlowStartAssignmentSupport {
    public static final String USER_FIELDS = "userFields";
    public static final String SYSTEM_FIELDS = "systemFields";
    public static final String PRE_DEFINED_FIELDS = "preDefinedFields";
    public static final String MEMORY = "memory";
    public static final String ASSIGNMENT = "assignment";
    public static final String ASSIGNMENT_SESSION = "session";
    public static final String ASSIGNMENT_PERMANENT = "permanent";
    public static final String REDIS_GLOBAL_VALS_NAME = "global.vals";
    public static final long DEFAULT_CONVERSATION_VARIABLE_TTL_SECONDS = 3L * 24 * 3600;

    private FlowStartAssignmentSupport() {}

    /**
     * conversationTtlSeconds.
     *
     * @return result
     * @since 0.1.0
     */

    public static long conversationTtlSeconds() {
        String env = System.getenv("CONVERSATION_VARIABLE_STORE_TIME");
        if (env == null || env.isBlank()) {
            env = System.getProperty("studio.dsl.conversation.ttl.seconds");
        }
        if (env == null || env.isBlank()) {
            return DEFAULT_CONVERSATION_VARIABLE_TTL_SECONDS;
        }
        try {
            return Long.parseLong(env.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_CONVERSATION_VARIABLE_TTL_SECONDS;
        }
    }

    /**
     * redisKey.
     *
     * @param workflowId workflowId
     * @param conversationId conversationId
     * @return result
     * @since 0.1.0
     */

    public static String redisKey(String workflowId, String conversationId) {
        return REDIS_GLOBAL_VALS_NAME + "." + workflowId + "." + conversationId;
    }

    /**
     * assignmentInputs.
     *
     * @param configs configs
     * @return result
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public static Object assignmentInputs(Map<String, Object> configs) {
        if (configs == null || !configs.containsKey(PRE_DEFINED_FIELDS)) {
        return List.of();
    }
        Object pre = configs.get(PRE_DEFINED_FIELDS);
        if (!(pre instanceof Map<?, ?> preMap)) {
            return List.of();
        }
        Object inputs = preMap.get("inputs");
        return inputs == null ? List.of() : inputs;
    }

    public static Map<String, Object> extractSessionVarDefs(Object data) {
        return extractAssignmentValues(data, ASSIGNMENT_SESSION);
    }

    public static Map<String, Object> extractAssignmentValues(Object data, String timeType) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (data instanceof List<?> list) {
            for (Object item : list) {
                result.putAll(extractAssignmentValues(item, timeType));
            }
        } else if (data instanceof Map<?, ?> raw) {
            Map<String, Object> map = stringKeyed(raw);
            String aging = String.valueOf(map.getOrDefault("aging_level", ""));
            if (ASSIGNMENT_PERMANENT.equals(aging)) {
                aging = ASSIGNMENT_SESSION;
            }
            if (ASSIGNMENT.equals(String.valueOf(map.get("storage_method"))) && timeType.equals(aging)) {
                if (!map.containsKey("schema")) {
                    if (map.containsKey("default_value") && map.containsKey("type")) {
                        String varId = String.valueOf(map.getOrDefault("id", ""));
                        if (!varId.isBlank()) {
                            result.put(
                                    varId,
                                    transformType(
                                            String.valueOf(map.get("type")).toLowerCase(),
                                            map.get("default_value")));
                        }
                    }
                } else {
                    result.putAll(extractAssignmentValues(map.get("schema"), timeType));
                }
            }
            if (map.containsKey("schema") && !ASSIGNMENT.equals(String.valueOf(map.get("storage_method")))) {
                result.putAll(extractAssignmentValues(map.get("schema"), timeType));
            }
        }
        return result;
    }

    /**
     * predefinedMemoryKeys.
     *
     * @param assignmentInputs assignmentInputs
     * @return result
     * @since 0.1.0
     */

    public static Set<String> predefinedMemoryKeys(Object assignmentInputs) {
        Set<String> keys = new LinkedHashSet<>();
        if (!(assignmentInputs instanceof List<?> list)) {
            return keys;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> raw) {
                Map<String, Object> map = stringKeyed(raw);
                if (MEMORY.equals(String.valueOf(map.get("id")))) {
                    Object schema = map.get("schema");
                    if (schema instanceof List<?> fields) {
                        for (Object field : fields) {
                            if (field instanceof Map<?, ?> f) {
                                Object id = f.get("id");
                                if (id != null) {
                                    keys.add(String.valueOf(id));
                                }
                            }
                        }
                    }
                }
            }
        }
        return keys;
    }

    /**
     * transformType.
     *
     * @param dataType dataType
     * @param dataValue dataValue
     * @return result
     * @since 0.1.0
     */

    public static Object transformType(String dataType, Object dataValue) {
        if (!"string".equals(dataType) && (dataValue == null || "".equals(dataValue))) {
        return null;
    }
        try {
            return switch (dataType) {
                case "integer" -> {
                    if (dataValue instanceof Boolean b) {
                yield b ? 1 : 0;
            }
                    yield dataValue instanceof Number n
                            ? n.intValue()
                            : Integer.parseInt(String.valueOf(dataValue));
                }
                case "boolean" -> {
                    // Python bool(x): non-empty string → True (incl. "false")
                    if (dataValue instanceof Boolean b) {
                        yield b;
                    }
                    if (dataValue instanceof String s) {
                        yield !s.isEmpty();
                    }
                    if (dataValue instanceof Number n) {
                        yield n.doubleValue() != 0.0;
                    }
                    yield true;
                }
                case "number" -> {
                    // Python DATA_TYPE_CONVERSION["number"] is a non-callable tuple → falls back to original;
                    // intended enum semantics: coerce via float
                    if (dataValue instanceof Number n) {
                        yield n.doubleValue();
                    }
                    yield Double.parseDouble(String.valueOf(dataValue));
                }
                case "string" -> String.valueOf(dataValue);
                case "object" -> {
                    if (dataValue instanceof Map<?, ?>) {
                        yield dataValue;
                    }
                    // Python dict(x) fails → return original
                    yield dataValue;
                }
                case "array" -> {
                    if (dataValue instanceof List<?>) {
                        yield dataValue;
                    }
                    // Python list(x) may succeed for iterable strings; keep original on non-List for Studio IR
                    yield dataValue;
                }
                default -> dataValue;
            };
        } catch (RuntimeException e) {
            return dataValue;
        }
    }

    /**
     * validateRequired.
     *
     * @param configs configs
     * @param inputs inputs
     * @since 0.1.0
     */

    public static void validateRequired(Map<String, Object> configs, Map<String, Object> inputs) {
        List<String> missing = new ArrayList<>();
        for (Map<String, Object> variable : userFieldInputs(configs)) {
            if (Boolean.TRUE.equals(variable.get("required")) || "true".equals(String.valueOf(variable.get("required")))) {
                String id = String.valueOf(variable.getOrDefault("id", ""));
                if (!id.isBlank() && !inputs.containsKey(id)) {
                    missing.add(id);
                }
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing required variables: " + missing);
        }
    }

    public static Map<String, Object> fillDefaults(Map<String, Object> configs, Map<String, Object> inputs) {
        Map<String, Object> defaults = new LinkedHashMap<>();
        for (Map<String, Object> variable : userFieldInputs(configs)) {
            if (!variable.containsKey("id")) {
                continue;
            }
            Object def = variable.get("default_value");
            if (def != null && !"".equals(def)) {
                defaults.put(String.valueOf(variable.get("id")), def);
            }
        }
        if (inputs == null || inputs.isEmpty()) {
            return defaults;
        }
        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        merged.putAll(inputs);
        return merged;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> userFieldInputs(Map<String, Object> configs) {
        Object uf = configs == null ? null : configs.get(USER_FIELDS);
        if (!(uf instanceof Map<?, ?> ufMap)) {
            return List.of();
        }
        Object inputs = ufMap.get("inputs");
        if (!(inputs instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(stringKeyed(m));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> startUserFieldsFromOutputs(Map<String, Object> configs) {
        Map<String, Object> startUserFields = new LinkedHashMap<>();
        Object uf = configs == null ? null : configs.get(USER_FIELDS);
        if (!(uf instanceof Map<?, ?> ufMap)) {
            return startUserFields;
        }
        Object outputs = ufMap.get("outputs");
        if (!(outputs instanceof List<?> list)) {
            return startUserFields;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> field = stringKeyed(m);
                startUserFields.put(String.valueOf(field.getOrDefault("id", "")), field.getOrDefault("default_value", ""));
            }
        }
        return startUserFields;
    }

    public static Map<String, Object> userInputMemoryVar(Map<String, Object> inputs) {
        Set<String> notVars = Set.of("query", "sys", "conversationId", "userId", "conversationHistory");
        Object gv = inputs.get("global_variables");
        if (!(gv instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        Map<String, Object> memory = new LinkedHashMap<>();
        stringKeyed(raw).forEach((k, v) -> {
            if (!notVars.contains(k)) {
                memory.put(k, v);
            }
        });
        return memory;
    }

    public static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
