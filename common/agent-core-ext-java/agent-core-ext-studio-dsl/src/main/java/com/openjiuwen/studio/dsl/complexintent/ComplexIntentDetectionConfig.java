/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.complexintent;

import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Config — 1:1 with Python {@code ComplexIntentDetectionConfig}.
 *
 * @since 2026-08-26
 */

public final class ComplexIntentDetectionConfig {
    /**
     * TYPE.
     * @since 0.1.0
     */
    public static final String TYPE = "EI.ComplexIntentDetection";

    private final String subNodeId;
    private final List<Map<String, Object>> branches;
    private final Map<String, Object> groups;
    private final String aggMode;
    private final boolean enableHistory;
    private final int chatHistoryMaxTurn;
    private final boolean enableKnowledge;
    private final double recallThreshold;
    private final Map<String, Object> llm;
    private final String prompt;
    private final Map<String, Object> kg;
    private final Map<String, Object> memory;
    private final Map<String, Object> rawConfigs;

    private ComplexIntentDetectionConfig(
            String subNodeId,
            List<Map<String, Object>> branches,
            Map<String, Object> groups,
            String aggMode,
            boolean enableHistory,
            int chatHistoryMaxTurn,
            boolean enableKnowledge,
            double recallThreshold,
            Map<String, Object> llm,
            String prompt,
            Map<String, Object> kg,
            Map<String, Object> memory,
            Map<String, Object> rawConfigs) {
        this.subNodeId = subNodeId;
        this.branches = branches;
        this.groups = groups;
        this.aggMode = aggMode;
        this.enableHistory = enableHistory;
        this.chatHistoryMaxTurn = chatHistoryMaxTurn;
        this.enableKnowledge = enableKnowledge;
        this.recallThreshold = recallThreshold;
        this.llm = llm;
        this.prompt = prompt;
        this.kg = kg;
        this.memory = memory;
        this.rawConfigs = rawConfigs;
    }

    /**
     * from.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static ComplexIntentDetectionConfig from(String nodeId, Map<String, Object> conf) {
        Map<String, Object> c = conf == null ? Map.of() : conf;
        List<Map<String, Object>> branches = listOfMaps(c.get("branches"));
        Map<String, Object> groups = mapOf(c.get("groups"));
        if (branches.isEmpty()) {
            throw new NodeExecutionException(
                    nodeId, TYPE, NodeCauseCode.NODE_CONFIG_INVALID, "branches must not be empty");
        }
        if (groups.isEmpty()) {
            throw new NodeExecutionException(
                    nodeId, TYPE, NodeCauseCode.NODE_CONFIG_INVALID, "groups must not be empty");
        }
        String subNodeId = str(first(c, "intentDetectionContainerNodeId", "sub_node_id"), "");
        return new ComplexIntentDetectionConfig(
                subNodeId,
                branches,
                groups,
                str(first(c, "agg_mode", "aggMode"), "first-non-null"),
                bool(first(c, "enable_history", "enableHistory"), false),
                intVal(first(c, "chat_history_max_turn", "chatHistoryMaxTurn"), 3),
                bool(first(c, "enable_knowledge", "enableKnowledge"), false),
                doubleVal(first(c, "recall_threshold", "recallThreshold"), 0.8),
                mapOf(c.get("llm")),
                str(c.get("prompt"), ""),
                mapOf(c.get("kg")),
                mapOf(c.get("memory")),
                new LinkedHashMap<>(c));
    }

    /**
     * IntentDetection-facing config (Python {@code _init_intent_detection}).
     *
     * @return result
     * @since 0.1.0
     */
    public Map<String, Object> toIntentDetectionConfigs() {
        Map<String, Object> intent = new LinkedHashMap<>(rawConfigs);
        if (!intent.containsKey("branches")) {
            List<Map<String, Object>> slim = new ArrayList<>();
            for (Map<String, Object> b : branches) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", b.get("id"));
                s.put("catalog", b.get("catalog"));
                slim.add(s);
            }
            intent.put("branches", slim);
        }
        if (!llm.isEmpty()) {
            intent.put("llm", llm);
        }
        if (!prompt.isBlank()) {
            intent.put("prompt", prompt);
        }
        intent.put("enableHistory", enableHistory);
        intent.put("chatHistoryMaxTurn", chatHistoryMaxTurn);
        intent.put("enableKnowledge", enableKnowledge);
        if (!kg.isEmpty()) {
            Map<String, Object> kgCopy = new LinkedHashMap<>(kg);
            Object category = kgCopy.get("category");
            if (category != null && String.valueOf(category).length() > 0) {
                kgCopy.put("filterString", "category:" + category);
            }
            intent.put("kg", kgCopy);
        }
        if (!memory.isEmpty()) {
            intent.put("memory", memory);
        }
        intent.put("recallThreshold", recallThreshold);
        return intent;
    }

    /**
     * subNodeId.
     *
     * @return result
     * @since 0.1.0
     */

    public String subNodeId() {
        return subNodeId;
    }
    public List<Map<String, Object>> branches() {
        return branches;
    }

    public Map<String, Object> groups() {
        return groups;
    }

    /**
     * aggMode.
     *
     * @return result
     * @since 0.1.0
     */

    public String aggMode() {
        return aggMode;
    }
    public Map<String, Object> rawConfigs() {
        return rawConfigs;
    }

    private static Object first(Map<String, Object> c, String a, String b) {
        if (c.containsKey(a) && c.get(a) != null) {
        return c.get(a);
    }
        return c.get(b);
    }

    private static String str(Object v, String d) {
        return v == null ? d : String.valueOf(v);
    }
    private static boolean bool(Object v, boolean d) {
        if (v == null) {
        return d;
    }
        if (v instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(v));
    }

    private static int intVal(Object v, int d) {
        if (v instanceof Number n) {
        return n.intValue();
    }
        if (v == null) {
            return d;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static double doubleVal(Object v, double d) {
        if (v instanceof Number n) {
        return n.doubleValue();
    }
        if (v == null) {
            return d;
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            return d;
        }
    }

    private static Map<String, Object> mapOf(Object v) {
        if (!(v instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, val) -> out.put(String.valueOf(k), val));
        return out;
    }

    private static List<Map<String, Object>> listOfMaps(Object v) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(v instanceof List<?> list)) {
            return out;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                out.add(mapOf(m));
            }
        }
        return out;
    }
}
