/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.control;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Studio End invoke path helpers (Python jiuwen.extension.workflow_node.end).
 *
 * @since 2026-08-25
 */
final class EndEngine {
    static final String OUTPUT_PREFIX = "#end_";
    static final String SPLIT_DELIMITER = "\u0001";
    static final String INVOKE_DONE_KEY = "end_invoke_executed";

    private EndEngine() {}

    static boolean alreadyInvoked(NodeSessionApi session) {
        if (session == null) {
            return false;
        }
        try {
            Object v = session.getState(INVOKE_DONE_KEY);
            return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    static void markInvoked(NodeSessionApi session) {
        if (session == null) {
            return;
        }
        try {
            session.updateState(Map.of(INVOKE_DONE_KEY, true));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    @SuppressWarnings("unchecked")
    static void applyTypeConversion(Map<String, Object> fields, List<Map<String, Object>> inputDefs) {
        if (inputDefs == null || inputDefs.isEmpty() || fields == null) {
            return;
        }
        for (Map<String, Object> def : inputDefs) {
            String id = String.valueOf(def.getOrDefault("id", ""));
            if (id.isBlank() || !fields.containsKey(id)) {
                continue;
            }
            String type = String.valueOf(def.getOrDefault("type", "string")).toLowerCase();
            fields.put(id, convertSimple(fields.get(id), type));
        }
    }

    static Object convertSimple(Object actual, String expectedType) {
        if (actual == null) {
            return switch (expectedType) {
                case "integer", "number", "boolean" -> null;
                case "array" -> null;
                case "object" -> null;
                default -> "";
            };
        }
        if ("array".equals(expectedType) && actual instanceof List<?> list) {
            return list.isEmpty() ? "" : String.valueOf(list);
        }
        if ("object".equals(expectedType) && actual instanceof Map<?, ?>) {
            Map<?, ?> m = (Map<?, ?>) actual;
            return m.isEmpty() ? "" : String.valueOf(m);
        }
        try {
            return switch (expectedType) {
                case "integer" -> actual instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(actual));
                case "number" -> actual instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(actual));
                case "boolean" -> {
                    if (actual instanceof Boolean b) {
                        yield b;
                    }
                    yield Boolean.parseBoolean(String.valueOf(actual));
                }
                default -> String.valueOf(actual);
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Map<String, Object> mapEndPrefixed(Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String k = e.getKey();
            if (k != null && k.startsWith(OUTPUT_PREFIX) && e.getValue() != null) {
                out.put(k.substring(OUTPUT_PREFIX.length()), e.getValue());
            }
        }
        return out;
    }

    static void stripEndPrefixed(Map<String, Object> fields) {
        List<String> keys = new ArrayList<>();
        for (String k : fields.keySet()) {
            if (k != null && k.startsWith(OUTPUT_PREFIX)) {
                keys.add(k);
            }
        }
        keys.forEach(fields::remove);
    }

    static Map<String, Object> metadata(
            Object answer,
            String nodeId,
            String nodeName,
            String nodeType,
            boolean shouldInterrupt,
            String think,
            Map<String, Object> outputs,
            String outputMode,
            String structAnswer) {
        Object answerValue = answer;
        if (answer instanceof Map<?, ?> m && m.containsKey("response")) {
            answerValue = m.get("response");
        }
        String finalThink = think == null ? "" : think;
        Object finalAnswer = answerValue;
        if (finalAnswer instanceof String s && s.contains(SPLIT_DELIMITER)) {
            String[] parts = s.split(SPLIT_DELIMITER, 2);
            finalAnswer = parts[0];
            if (parts.length > 1 && finalThink.isBlank()) {
                finalThink = parts[1];
            }
        }
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("answer", finalAnswer);
        base.put("node_id", nodeId);
        base.put("node_name", nodeName);
        base.put("node_type", nodeType);
        base.put("should_interrupt", shouldInterrupt);
        if (structAnswer != null && !structAnswer.isBlank()) {
            base.put("origin_answer", structAnswer);
        }
        if (finalThink != null && !finalThink.isBlank()) {
            base.put("think", finalThink);
        }
        if (outputMode != null && !outputMode.isBlank()) {
            base.put("output_mode", outputMode);
        }
        if (outputs != null && !outputs.isEmpty()) {
            base.put("userFields", outputs);
        }
        return base;
    }

    static String renderTemplate(String template, Map<String, Object> vars) {
        return TemplateRenderer.render(template == null ? "" : template, vars);
    }

    static void emitEndFrames(NodeSessionApi session, Map<String, Object> withThink, Map<String, Object> withoutThink) {
        if (session == null) {
            return;
        }
        try {
            session.writeCustomStream(
                    Map.of("type", StudioStreamFrames.MESSAGE_NODE_END, "index", 1, "data", withThink));
            StudioStreamFrames.emitWorkflowEnd(session, withoutThink);
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> inputDefsFromConfigs(Map<String, Object> configs) {
        Object uf = configs.get("userFields");
        if (!(uf instanceof Map<?, ?> m)) {
            return List.of();
        }
        Object inputs = m.get("inputs");
        if (!(inputs instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> im) {
                Map<String, Object> d = new LinkedHashMap<>();
                im.forEach((k, v) -> d.put(String.valueOf(k), v));
                out.add(d);
            }
        }
        return out;
    }
}
