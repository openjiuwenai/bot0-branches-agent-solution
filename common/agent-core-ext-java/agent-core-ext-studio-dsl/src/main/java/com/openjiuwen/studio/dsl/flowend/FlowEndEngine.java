/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowend;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.adapter.StudioStreamFrames;
import com.openjiuwen.studio.dsl.util.TemplateRenderer;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Strict 1:1 helpers for Python {@code jiuwen/extension/workflow_node/end.py}.
 *
 * @since 2026-08-25
 */

public final class FlowEndEngine {

    /**
     * OUTPUT_PREFIX.
     *
     * @since 0.1.0
     */

    public static final String OUTPUT_PREFIX = "#end_";

    /**
     * SPLIT_DELIMITER.
     *
     * @since 0.1.0
     */

    public static final String SPLIT_DELIMITER = "\u0001";

    /** @deprecated use {@link #scopedKey(String, String)} — kept for tests referencing raw suffix */
    public static final String INVOKE_DONE_KEY = "invoke_executed";

    /** @deprecated use scoped keys */
    public static final String STREAM_DONE_KEY = "stream_executed";

    /** @deprecated use scoped keys */
    public static final String COLLECT_DONE_KEY = "collect_executed";

    /** @deprecated use scoped keys */
    public static final String TRANSFORM_DONE_KEY = "transform_executed";

    private static final Map<MixKey, FlowEndMixCoordinator> MIX_BY_SESSION_NODE = new ConcurrentHashMap<>();

    /**
     * * * Python {@code _TEMPLATE_SPLIT_PATTERN}.
     */
    public static final Pattern TEMPLATE_SPLIT_PATTERN = Pattern.compile("(\\{\\{[^{}]*?\\}\\})");

    private FlowEndEngine() {}

    /**
     * alreadyInvoked.
     *
     * @param session session
     * @param nodeId nodeId
     * @return result
     * @since 0.1.0
     */

    public static boolean alreadyInvoked(NodeSessionApi session, String nodeId) {
        return sessionFlag(session, scopedKey(nodeId, INVOKE_DONE_KEY));
    }

    /**
     * markInvoked.
     *
     * @param session session
     * @param nodeId nodeId
     * @since 0.1.0
     */

    public static void markInvoked(NodeSessionApi session, String nodeId) {
        markSessionFlag(session, scopedKey(nodeId, INVOKE_DONE_KEY));
    }

    /**
     * alreadyStreamed.
     *
     * @param session session
     * @param nodeId nodeId
     * @return result
     * @since 0.1.0
     */

    public static boolean alreadyStreamed(NodeSessionApi session, String nodeId) {
        return sessionFlag(session, scopedKey(nodeId, STREAM_DONE_KEY));
    }

    /**
     * markStreamed.
     *
     * @param session session
     * @param nodeId nodeId
     * @since 0.1.0
     */

    public static void markStreamed(NodeSessionApi session, String nodeId) {
        markSessionFlag(session, scopedKey(nodeId, STREAM_DONE_KEY));
    }

    /**
     * alreadyCollected.
     *
     * @param session session
     * @param nodeId nodeId
     * @return result
     * @since 0.1.0
     */

    public static boolean alreadyCollected(NodeSessionApi session, String nodeId) {
        return sessionFlag(session, scopedKey(nodeId, COLLECT_DONE_KEY));
    }

    /**
     * markCollected.
     *
     * @param session session
     * @param nodeId nodeId
     * @since 0.1.0
     */

    public static void markCollected(NodeSessionApi session, String nodeId) {
        markSessionFlag(session, scopedKey(nodeId, COLLECT_DONE_KEY));
    }

    /**
     * alreadyTransformed.
     *
     * @param session session
     * @param nodeId nodeId
     * @return result
     * @since 0.1.0
     */

    public static boolean alreadyTransformed(NodeSessionApi session, String nodeId) {
        return sessionFlag(session, scopedKey(nodeId, TRANSFORM_DONE_KEY));
    }

    /**
     * markTransformed.
     *
     * @param session session
     * @param nodeId nodeId
     * @since 0.1.0
     */

    public static void markTransformed(NodeSessionApi session, String nodeId) {
        markSessionFlag(session, scopedKey(nodeId, TRANSFORM_DONE_KEY));
    }

    /**
     * Per (session, nodeId) mix coordinator — avoids cross-node / cross-run leakage.
     *
     * @param session session
     * @param nodeId nodeId
     * @return result
     * @since 0.1.0
     */

    public static FlowEndMixCoordinator mixCoordinator(NodeSessionApi session, String nodeId) {
        if (session == null) {
        return new FlowEndMixCoordinator();
    }
        return MIX_BY_SESSION_NODE.computeIfAbsent(new MixKey(session, normalizeNodeId(nodeId)), k -> new FlowEndMixCoordinator());
    }

    /**
     * scopedKey.
     *
     * @param nodeId nodeId
     * @param suffix suffix
     * @return result
     * @since 0.1.0
     */

    public static String scopedKey(String nodeId, String suffix) {
        return "end_" + normalizeNodeId(nodeId) + "_" + suffix;
    }
    private static String normalizeNodeId(String nodeId) {
        return nodeId == null || nodeId.isBlank() ? "n/a" : nodeId;
    }
    private static boolean sessionFlag(NodeSessionApi session, String key) {
        if (session == null) {
        return false;
    }
        try {
            Object v = session.getState(key);
            return Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v));
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            return false;
        }
    }

    private static void markSessionFlag(NodeSessionApi session, String key) {
        if (session == null) {
        return;
    }
        try {
            session.updateState(Map.of(key, true));
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock
        }
    }

    /**
     * applyTypeConversion.
     *
     * @param fields fields
     * @param inputDefs inputDefs
     * @since 0.1.0
     */

    @SuppressWarnings("unchecked")
    public static void applyTypeConversion(Map<String, Object> fields, List<Map<String, Object>> inputDefs) {
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

    /**
     * convertSimple.
     *
     * @param actual actual
     * @param expectedType expectedType
     * @return result
     * @since 0.1.0
     */

    public static Object convertSimple(Object actual, String expectedType) {
        // Python: skip AsyncGenerator / __aiter__ — JVM: skip live Iterator (stream stand-in)
        if (actual instanceof Iterator) {
        return actual;
    }
        if (actual == null) {
            return switch (expectedType) {
                case "integer", "number", "boolean", "array", "object" -> null;
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
                case "integer" -> {
                    // Python: bool is int subclass — force convert when expecting integer
                    if (actual instanceof Boolean b) {
                yield b ? 1 : 0;
            }
                    yield actual instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(actual));
                }
                case "number" -> actual instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(actual));
                case "boolean" -> {
                    // Python bool(x): non-empty string → True (incl. "false"); 0 → False
                    if (actual instanceof Boolean b) {
                        yield b;
                    }
                    if (actual instanceof String s) {
                        yield !s.isEmpty();
                    }
                    if (actual instanceof Number n) {
                        yield n.doubleValue() != 0.0;
                    }
                    yield true;
                }
                default -> String.valueOf(actual);
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Python {@code _TEMPLATE_SPLIT_PATTERN.split(responseTemplate)}.
     *
     * @param responseTemplate responseTemplate
     * @return result
     * @since 0.1.0
     */

    public static List<String> templateParts(String responseTemplate) {
        if (responseTemplate == null || responseTemplate.isEmpty()) {
        return List.of();
    }
        return List.of(TEMPLATE_SPLIT_PATTERN.split(responseTemplate, -1));
    }

    /**
     * Python {@code _should_emit_start_marker}.
     *
     * @param responseTemplate responseTemplate
     * @return result
     * @since 0.1.0
     */

    public static boolean shouldEmitStartMarker(String responseTemplate) {
        List<String> parts = templateParts(responseTemplate);
        return !parts.isEmpty() && parts.get(0).isEmpty();
    }

    /**
     * Python {@code _should_emit_end_marker}.
     *
     * @param responseTemplate responseTemplate
     * @return result
     * @since 0.1.0
     */

    public static boolean shouldEmitEndMarker(String responseTemplate) {
        List<String> parts = templateParts(responseTemplate);
        return !parts.isEmpty() && parts.get(parts.size() - 1).isEmpty();
    }

    /**
     * Python {@code session.get_global_state("query") or ""}.
     *
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public static String queryOf(NodeSessionApi session) {
        if (session == null) {
        return "";
    }
        try {
            Object q = session.getGlobalState("query");
            return q == null ? "" : String.valueOf(q);
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            return "";
        }
    }

    /**
     * * Python {@code user_fields = {**inputs, **outputs, **{"query": query}}} plus Studio {@code __terminal__}.
     *
     * @param inputs inputs
     * @param outputs outputs
     * @param query query
     * @param endInterrupt endInterrupt
     * @return result
     * @since 0.1.0
     */

    public static Map<String, Object> buildUserFields(
            Map<String, Object> inputs, Map<String, Object> outputs, String query, boolean endInterrupt) {
        Map<String, Object> userOut = new LinkedHashMap<>(inputs == null ? Map.of() : inputs);
        if (outputs != null) {
            userOut.putAll(outputs);
        }
        userOut.put("query", query == null ? "" : query);
        userOut.put("__terminal__", true);
        userOut.put("should_interrupt", endInterrupt);
        return userOut;
    }

    public static Map<String, Object> mapEndPrefixed(Map<String, Object> fields) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String k = e.getKey();
            if (k != null && k.startsWith(OUTPUT_PREFIX) && e.getValue() != null) {
                out.put(k.substring(OUTPUT_PREFIX.length()), e.getValue());
            }
        }
        return out;
    }

    /**
     * stripEndPrefixed.
     *
     * @param fields fields
     * @since 0.1.0
     */

    public static void stripEndPrefixed(Map<String, Object> fields) {
        List<String> keys = new ArrayList<>();
        for (String k : fields.keySet()) {
            if (k != null && k.startsWith(OUTPUT_PREFIX)) {
                keys.add(k);
            }
        }
        keys.forEach(fields::remove);
    }

    public static Map<String, Object> metadata(
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

    /**
     * renderTemplate.
     *
     * @param template template
     * @param vars vars
     * @return result
     * @since 0.1.0
     */

    public static String renderTemplate(String template, Map<String, Object> vars) {
        return TemplateRenderer.render(template == null ? "" : template, vars);
    }

    /**
     * emitEndFrames.
     *
     * @param session session
     * @param withThink withThink
     * @param withoutThink withoutThink
     * @since 0.1.0
     */

    public static void emitEndFrames(NodeSessionApi session, Map<String, Object> withThink, Map<String, Object> withoutThink) {
        if (session == null) {
        return;
    }
        try {
            session.writeCustomStream(
                    Map.of("type", StudioStreamFrames.MESSAGE_NODE_END, "index", 1, "data", withThink));
            StudioStreamFrames.emitWorkflowEnd(session, withoutThink);
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> inputDefsFromConfigs(Map<String, Object> configs) {
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

    private record MixKey(NodeSessionApi session, String nodeId) {
        }
    }