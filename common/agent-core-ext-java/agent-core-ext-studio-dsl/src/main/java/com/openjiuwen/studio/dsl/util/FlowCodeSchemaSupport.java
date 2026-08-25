/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.util;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FlowCode schema coerce + CODE_BLACK_LIST (Python {@code flow_code.py}).
 *
 * @since 2026-08-25
 */
public final class FlowCodeSchemaSupport {
    private FlowCodeSchemaSupport() {}

    /**
     * checkBlacklist — env {@code CODE_BLACK_LIST} JSON array of forbidden substrings.
     *
     * @param nodeId nodeId
     * @param code code
     */
    public static void checkBlacklist(String nodeId, String code) {
        if (code == null || code.isBlank()) {
            return;
        }
        String raw = System.getenv("CODE_BLACK_LIST");
        if (raw == null || raw.isBlank()) {
            raw = System.getProperty("CODE_BLACK_LIST", "[]");
        }
        List<String> black = parseStringArray(raw);
        if (black.isEmpty()) {
            return;
        }
        String clean = code.replaceAll("\\s+", " ").trim();
        for (String kw : black) {
            if (kw != null && !kw.isEmpty() && clean.contains(kw)) {
                throw new NodeExecutionException(
                        nodeId,
                        "jiuwen.code",
                        NodeCauseCode.NODE_INVOKE_FAILED,
                        kw + " is in the code black list");
            }
        }
    }

    /**
     * coerceInputs from IR userFields.inputs list or map schema.
     *
     * @param inputs inputs
     * @param configs node configs
     * @return result
     */
    public static Map<String, Object> coerceInputs(Map<String, Object> inputs, Map<String, Object> configs) {
        if (inputs == null) {
            return Map.of();
        }
        Object userFields = configs == null ? null : configs.get("userFields");
        List<Map<String, Object>> listSchema = listSchema(userFields, "inputs");
        if (listSchema != null) {
            return TypeCoercer.coerceByFieldList(inputs, listSchema, true);
        }
        Object schema = configs == null
                ? null
                : configs.getOrDefault("userFieldsSchema", configs.get("userFields"));
        if (schema instanceof Map<?, ?> m && !(m.containsKey("inputs") || m.containsKey("outputs"))) {
            Map<String, Object> sch = new LinkedHashMap<>();
            m.forEach((k, v) -> sch.put(String.valueOf(k), v));
            return TypeCoercer.coerceMap(inputs, sch);
        }
        return new LinkedHashMap<>(inputs);
    }

    /**
     * coerceOutputs from IR userFields.outputs list.
     *
     * @param outputs outputs
     * @param configs configs
     * @return result
     */
    public static Map<String, Object> coerceOutputs(Map<String, Object> outputs, Map<String, Object> configs) {
        if (outputs == null) {
            return Map.of();
        }
        Object userFields = configs == null ? null : configs.get("userFields");
        List<Map<String, Object>> listSchema = listSchema(userFields, "outputs");
        if (listSchema != null) {
            return TypeCoercer.coerceByFieldList(outputs, listSchema, false);
        }
        return new LinkedHashMap<>(outputs);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listSchema(Object userFields, String key) {
        if (!(userFields instanceof Map<?, ?> uf)) {
            return null;
        }
        Object list = uf.get(key);
        if (!(list instanceof List<?> raw) || raw.isEmpty()) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : raw) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> cast = new LinkedHashMap<>();
                m.forEach((k, v) -> cast.put(String.valueOf(k), v));
                out.add(cast);
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> parseStringArray(String raw) {
        String s = raw.trim();
        if ("[]".equals(s) || s.isEmpty()) {
            return List.of();
        }
        // Minimal: ["a","b"] or [a,b]
        if (s.startsWith("[") && s.endsWith("]")) {
            String inner = s.substring(1, s.length() - 1).trim();
            if (inner.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            Matcher m = Pattern.compile("\"((?:\\\\.|[^\"\\\\])*)\"|([^,\\s\\]]+)").matcher(inner);
            while (m.find()) {
                if (m.group(1) != null) {
                    out.add(m.group(1).replace("\\\"", "\""));
                } else if (m.group(2) != null) {
                    out.add(m.group(2).trim());
                }
            }
            return out;
        }
        return List.of(s);
    }
}
