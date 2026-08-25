/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

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
final class StartAssignmentSupport {
    static final String USER_FIELDS = "userFields";
    static final String SYSTEM_FIELDS = "systemFields";
    static final String PRE_DEFINED_FIELDS = "preDefinedFields";
    static final String MEMORY = "memory";
    static final String ASSIGNMENT = "assignment";
    static final String ASSIGNMENT_SESSION = "session";
    static final String ASSIGNMENT_PERMANENT = "permanent";
    static final String REDIS_GLOBAL_VALS_NAME = "global.vals";
    static final long DEFAULT_CONVERSATION_VARIABLE_TTL_SECONDS = 3L * 24 * 3600;

    private StartAssignmentSupport() {}

    static long conversationTtlSeconds() {
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

    static String redisKey(String workflowId, String conversationId) {
        return REDIS_GLOBAL_VALS_NAME + "." + workflowId + "." + conversationId;
    }

    @SuppressWarnings("unchecked")
    static Object assignmentInputs(Map<String, Object> configs) {
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

    static Map<String, Object> extractSessionVarDefs(Object data) {
        return extractAssignmentValues(data, ASSIGNMENT_SESSION);
    }

    static Map<String, Object> extractAssignmentValues(Object data, String timeType) {
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

    static Set<String> predefinedMemoryKeys(Object assignmentInputs) {
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

    static Object transformType(String dataType, Object dataValue) {
        if (!"string".equals(dataType) && (dataValue == null || "".equals(dataValue))) {
            return null;
        }
        try {
            return switch (dataType) {
                case "integer" -> dataValue instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(dataValue));
                case "boolean" -> dataValue instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(dataValue));
                case "number" -> dataValue instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(dataValue));
                case "string" -> String.valueOf(dataValue);
                case "object" -> dataValue instanceof Map<?, ?> ? dataValue : Map.of();
                case "array" -> dataValue instanceof List<?> ? dataValue : List.of();
                default -> dataValue;
            };
        } catch (RuntimeException e) {
            return dataValue;
        }
    }

    static void validateRequired(Map<String, Object> configs, Map<String, Object> inputs) {
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

    static Map<String, Object> fillDefaults(Map<String, Object> configs, Map<String, Object> inputs) {
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
    static List<Map<String, Object>> userFieldInputs(Map<String, Object> configs) {
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
    static Map<String, Object> startUserFieldsFromOutputs(Map<String, Object> configs) {
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

    static Map<String, Object> userInputMemoryVar(Map<String, Object> inputs) {
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

    static Map<String, Object> stringKeyed(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }
}
