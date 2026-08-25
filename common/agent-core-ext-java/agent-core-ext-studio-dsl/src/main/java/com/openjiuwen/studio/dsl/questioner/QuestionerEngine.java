/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.rails.RailsRegistry;
import com.openjiuwen.studio.dsl.util.TypeCoercer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Questioner invoke engine — agent_runtime Flow Questioner main path (no Redis trace / reflection).
 *
 * @since 2026-08-25
 */
public final class QuestionerEngine {
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*\\}\\}");
    private static final List<String> USER_CONFIRM_INFO = List.of("确认", "是的", "好的", "yes", "y", "ok", "confirm");
    private static final List<String> USER_BREAK_INFO = List.of("退出", "取消", "结束", "exit", "quit", "cancel", "break");

    private final String nodeId;
    private final QuestionerConfig config;

    /**
     * QuestionerEngine.
     *
     * @param nodeId nodeId
     * @param config config
     */
    public QuestionerEngine(String nodeId, QuestionerConfig config) {
        this.nodeId = nodeId;
        this.config = config;
    }

    /**
     * invoke.
     *
     * @param inputs inputs
     * @param session session (nullable)
     * @return userFields map (already mapped names when finished); may still be interacting
     */
    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session) {
        Map<String, Object> in = inputs == null ? Map.of() : inputs;
        Map<String, Object> userFields = userFieldsOf(in);

        // Short-circuit: already answered in inputs (host resume payload)
        if (userFields.containsKey("answer")
                || userFields.containsKey("userAnswer")
                || userFields.containsKey("USER_RESPONSE")) {
            return finishFromExisting(userFields);
        }

        QuestionerState state = loadState(session, in);
        boolean resuming = state.isUndergoingInteraction() || bool(in.get("__single_debug_recovery__"));

        Map<String, Object> result;
        if (!resuming) {
            result = handleStart(userFields, in);
        } else {
            if (!state.isUndergoingInteraction()) {
                // recover status from inputs if present
                state.setStatus(QuestionerState.USER_INTERACT);
            }
            result = handleUserInteract(state, userFields, in, session);
        }

        // After start/interact handlers, state may have been mutated via result meta
        QuestionerState current = stateFromResult(result, state);

        if (QuestionerState.USER_INTERACT.equals(current.status())) {
            storeState(session, current);
            publishInterrupt(session, current.question());
            collectViaInteract(session, current);
            // If interact returned synchronously (test/fake), continue as resume once
            if (session != null) {
                Object reply = tryLatestReply(session, current.question());
                if (reply != null) {
                    Map<String, Object> resumeIn = new LinkedHashMap<>(in);
                    resumeIn.put("query", reply);
                    resumeIn.put("__single_debug_recovery__", true);
                    current.incrementResponseNum();
                    result = handleUserInteract(current, userFields, resumeIn, session);
                    current = stateFromResult(result, current);
                    if (!QuestionerState.USER_INTERACT.equals(current.status())) {
                        storeState(session, new QuestionerState());
                        return convertOutputNames(result);
                    }
                }
            }
            Map<String, Object> hang = new LinkedHashMap<>(result);
            hang.put("questionerState", "INPUT_REQUIRED");
            hang.put("hangState", "INPUT_REQUIRED");
            hang.put("STATUS", "INPUT_REQUIRED");
            hang.put("QUESTION", current.question());
            hang.put("question", current.question());
            return hang;
        }

        storeState(session, new QuestionerState());
        return convertOutputNames(result);
    }

    private Map<String, Object> handleStart(Map<String, Object> userFields, Map<String, Object> inputs) {
        if (config.hasQuestionContent()) {
            String question = formatTemplate(config.questionContent(), userFields);
            QuestionerState state = new QuestionerState();
            state.setStatus(QuestionerState.USER_INTERACT);
            state.setQuestion(question);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("question", question);
            out.put("_state", state.toMap());
            return out;
        }
        if (config.needExtractFields()) {
            Map<String, Object> extracted = extractFields(queryOf(inputs, userFields), userFields);
            applyDefaults(extracted);
            RailsResult rails = applyRails(extracted, queryOf(inputs, userFields));
            mergeExtracted(new QuestionerState(), rails.arguments());
            QuestionerState state = new QuestionerState();
            state.extractedFields().putAll(rails.arguments());
            state.fieldsCheckFailed().addAll(rails.failedFields());
            boolean continueAsk = shouldContinueAsk(state);
            Map<String, Object> out = new LinkedHashMap<>(rails.arguments());
            if (continueAsk) {
                String q = constructContinueAsk(state);
                state.setStatus(QuestionerState.USER_INTERACT);
                state.setQuestion(q);
                out.put("question", q);
            } else {
                state.setStatus(QuestionerState.END);
                out.put("status", "end");
            }
            out.put("_state", state.toMap());
            return out;
        }
        throw new NodeExecutionException(
                nodeId,
                "jiuwen.questioner",
                NodeCauseCode.NODE_CONFIG_INVALID,
                "question_content is empty and no extractable fields are configured");
    }

    private Map<String, Object> handleUserInteract(
            QuestionerState state, Map<String, Object> userFields, Map<String, Object> inputs, NodeSessionApi session) {
        String query = queryOf(inputs, userFields);
        if (bool(inputs.get("__single_debug_recovery__"))) {
            // response_num already incremented by caller when sync interact
            if (state.responseNum() == 0) {
                state.incrementResponseNum();
            }
        } else if (session != null) {
            Object reply = collectViaInteract(session, state);
            if (reply != null) {
                query = String.valueOf(reply);
                state.incrementResponseNum();
            }
        }

        if (config.allowNodeBreak() && matchesKeyword(query, USER_BREAK_INFO)) {
            state.setUserBreak(true);
            state.setStatus(QuestionerState.END);
            Map<String, Object> out = new LinkedHashMap<>(state.extractedFields());
            out.put("user_response", query);
            out.put("question", state.question());
            out.put("status", "break");
            out.put("_state", state.toMap());
            return out;
        }
        if (config.allowNodeConfirm()
                && state.needUserConfirm()
                && matchesKeyword(query, USER_CONFIRM_INFO)) {
            state.setStatus(QuestionerState.END);
            Map<String, Object> out = new LinkedHashMap<>(state.extractedFields());
            out.put("user_response", query);
            out.put("question", state.question());
            out.put("status", "confirmed");
            out.put("_state", state.toMap());
            return out;
        }

        // Path A: question only
        if (config.hasQuestionContent() && !config.needExtractFields()) {
            state.setStatus(QuestionerState.END);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user_response", query);
            out.put("question", state.question());
            out.put("status", "end");
            out.put("_state", state.toMap());
            return out;
        }

        // Path B: extract
        Map<String, Object> extracted = extractFields(query, userFields);
        // merge previous
        Map<String, Object> merged = new LinkedHashMap<>(state.extractedFields());
        extracted.forEach((k, v) -> {
            if (v != null && !"".equals(v) && !"null".equalsIgnoreCase(String.valueOf(v))) {
                merged.put(k, v);
            }
        });
        applyDefaults(merged);
        RailsResult rails = applyRails(merged, query);
        state.extractedFields().clear();
        state.extractedFields().putAll(rails.arguments());
        state.fieldsCheckFailed().clear();
        state.fieldsCheckFailed().addAll(rails.failedFields());

        boolean continueAsk = shouldContinueAsk(state);
        Map<String, Object> out = new LinkedHashMap<>(rails.arguments());
        out.put("user_response", query);
        if (continueAsk) {
            String q = constructContinueAsk(state);
            state.setStatus(QuestionerState.USER_INTERACT);
            state.setQuestion(q);
            out.put("question", q);
        } else {
            state.setStatus(QuestionerState.END);
            out.put("question", state.question());
            out.put("status", "end");
            // failed fields → ""
            for (String f : state.fieldsCheckFailed()) {
                out.put(f, "");
            }
        }
        out.put("_state", state.toMap());
        return out;
    }

    private boolean shouldContinueAsk(QuestionerState state) {
        List<QuestionerField> missing = nonExtracted(state);
        if (missing.isEmpty() && state.fieldsCheckFailed().isEmpty()) {
            return false;
        }
        if (state.responseNum() >= config.maxResponse()) {
            // force end — clear failed
            state.fieldsCheckFailed().clear();
            return false;
        }
        return true;
    }

    private List<QuestionerField> nonExtracted(QuestionerState state) {
        List<QuestionerField> missing = new ArrayList<>();
        for (QuestionerField f : config.keyFields()) {
            if (!f.required()) {
                continue;
            }
            Object v = state.extractedFields().get(f.fieldName());
            if (v == null || "".equals(v) || "null".equalsIgnoreCase(String.valueOf(v))) {
                missing.add(f);
            } else if (state.fieldsCheckFailed().contains(f.fieldName())) {
                missing.add(f);
            }
        }
        return missing;
    }

    private String constructContinueAsk(QuestionerState state) {
        List<QuestionerField> missing = nonExtracted(state);
        String names = missing.stream().map(QuestionerField::cnFieldName).collect(Collectors.joining("、"));
        if (!config.autoAskTemplate().isBlank()) {
            return config.autoAskTemplate().replace("{unextracted_cn_field_names}", names);
        }
        if ("en".equalsIgnoreCase(config.acceptLanguage())) {
            return "Please provide: " + names;
        }
        return "请补充以下信息：" + names;
    }

    private Map<String, Object> extractFields(String query, Map<String, Object> userFields) {
        if (!config.mockExtractedFields().isEmpty()) {
            return new LinkedHashMap<>(config.mockExtractedFields());
        }
        // JSON object in query
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> parsed =
                        (Map<String, Object>)
                                com.openjiuwen.studio.dsl.python.SubprocessPythonCodeExecutor
                                        .parseJsonObject(trimmed);
                return filterKnown(parsed);
            } catch (Exception ignored) {
                // fall through
            }
        }
        // single required field → whole query
        List<QuestionerField> required =
                config.keyFields().stream().filter(QuestionerField::required).toList();
        if (required.size() == 1 && trimmed.length() > 0) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put(required.get(0).fieldName(), convertType(trimmed, required.get(0).type()));
            return one;
        }
        // copy overlapping keys from userFields
        Map<String, Object> out = new LinkedHashMap<>();
        for (QuestionerField f : config.keyFields()) {
            if (userFields.containsKey(f.fieldName())) {
                out.put(f.fieldName(), convertType(userFields.get(f.fieldName()), f.type()));
            }
        }
        return out;
    }

    private Map<String, Object> filterKnown(Map<String, Object> parsed) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (QuestionerField f : config.keyFields()) {
            if (parsed.containsKey(f.fieldName())) {
                out.put(f.fieldName(), convertType(parsed.get(f.fieldName()), f.type()));
            }
        }
        return out;
    }

    private void applyDefaults(Map<String, Object> fields) {
        for (QuestionerField f : config.keyFields()) {
            Object v = fields.get(f.fieldName());
            if ((v == null || "".equals(v)) && f.defaultValue() != null) {
                fields.put(f.fieldName(), f.defaultValue());
            }
        }
    }

    private RailsResult applyRails(Map<String, Object> fields, String userInput) {
        Map<String, Object> before = new LinkedHashMap<>(fields);
        if (config.railsConfig() == null || config.railsConfig().isEmpty()) {
            return new RailsResult(before, List.of());
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("arguments", before);
        ctx.put("user_input", userInput == null ? "" : userInput);
        Map<String, Object> after = RailsRegistry.executeRails(config.railsConfig(), ctx);
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Object> e : before.entrySet()) {
            Object b = e.getValue();
            Object a = after.get(e.getKey());
            if (b != null && !"".equals(b) && a == null) {
                failed.add(e.getKey());
            }
        }
        // type convert remaining
        for (QuestionerField f : config.keyFields()) {
            if (after.containsKey(f.fieldName()) && after.get(f.fieldName()) != null) {
                after.put(f.fieldName(), convertType(after.get(f.fieldName()), f.type()));
            }
        }
        return new RailsResult(after, failed);
    }

    private static Object convertType(Object value, String type) {
        return TypeCoercer.coerce(value, type, null, false);
    }

    private static void mergeExtracted(QuestionerState state, Map<String, Object> args) {
        state.extractedFields().putAll(args);
    }

    private QuestionerState stateFromResult(Map<String, Object> result, QuestionerState fallback) {
        Object raw = result.get("_state");
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> cast = new LinkedHashMap<>();
            m.forEach((k, v) -> cast.put(String.valueOf(k), v));
            return QuestionerState.fromMap(cast);
        }
        return fallback;
    }

    private Map<String, Object> convertOutputNames(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if ("_state".equals(e.getKey())) {
                continue;
            }
            String key = switch (e.getKey()) {
                case "user_response" -> "USER_RESPONSE";
                case "question" -> "QUESTION";
                case "status" -> "STATUS";
                default -> e.getKey();
            };
            out.put(key, e.getValue());
        }
        out.put("questionerState", "answered");
        out.put("hangState", "Continue");
        return out;
    }

    private Map<String, Object> finishFromExisting(Map<String, Object> uf) {
        Map<String, Object> out = new LinkedHashMap<>(uf);
        out.putIfAbsent("questionerState", "answered");
        out.putIfAbsent("hangState", "Continue");
        if (uf.containsKey("answer") && !uf.containsKey("USER_RESPONSE")) {
            out.put("USER_RESPONSE", uf.get("answer"));
        }
        if (uf.containsKey("userAnswer") && !uf.containsKey("USER_RESPONSE")) {
            out.put("USER_RESPONSE", uf.get("userAnswer"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private QuestionerState loadState(NodeSessionApi session, Map<String, Object> inputs) {
        Object fromInput = inputs.get(QuestionerState.KEY);
        if (fromInput instanceof Map<?, ?> m) {
            Map<String, Object> cast = new LinkedHashMap<>();
            m.forEach((k, v) -> cast.put(String.valueOf(k), v));
            return QuestionerState.fromMap(cast);
        }
        if (session != null) {
            try {
                Object s = session.getState(QuestionerState.KEY);
                if (s instanceof Map<?, ?> m) {
                    Map<String, Object> cast = new LinkedHashMap<>();
                    m.forEach((k, v) -> cast.put(String.valueOf(k), v));
                    return QuestionerState.fromMap(cast);
                }
            } catch (RuntimeException ignored) {
                // mock
            }
        }
        return new QuestionerState();
    }

    private void storeState(NodeSessionApi session, QuestionerState state) {
        if (session == null) {
            return;
        }
        try {
            session.updateState(Map.of(QuestionerState.KEY, state.toMap()));
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private void publishInterrupt(NodeSessionApi session, String question) {
        if (session == null) {
            return;
        }
        try {
            Map<String, Object> custom = new LinkedHashMap<>();
            custom.put("answer", question);
            custom.put("result", question);
            custom.put("node_id", nodeId);
            custom.put("node_type", "jiuwen.questioner");
            custom.put("should_interrupt", true);
            session.writeCustomStream(Map.of("type", "partial_content", "index", 0, "data", custom));
            session.writeCustomStream(Map.of("type", "message_end", "index", 1, "data", custom));
            session.updateState(Map.of(
                    "hangState",
                    "INPUT_REQUIRED",
                    "questionerState",
                    "INPUT_REQUIRED",
                    "feat008",
                    "INPUT_REQUIRED",
                    "question",
                    question,
                    "nodeId",
                    nodeId));
        } catch (RuntimeException ignored) {
            // mock / no stream
        }
    }

    private Object collectViaInteract(NodeSessionApi session, QuestionerState state) {
        if (session == null) {
            return null;
        }
        try {
            Map<String, Object> ask = new LinkedHashMap<>();
            ask.put("type", "INPUT_REQUIRED");
            ask.put("question", state.question());
            ask.put("nodeId", nodeId);
            return session.interact(ask);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private Object tryLatestReply(NodeSessionApi session, String question) {
        try {
            return session.userLatestInput(question);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    static String formatTemplate(String template, Map<String, Object> userFields) {
        Matcher m = TEMPLATE_VAR.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String path = m.group(1);
            Object val = resolvePath(userFields, path);
            m.appendReplacement(sb, Matcher.quoteReplacement(val == null ? "" : String.valueOf(val)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static Object resolvePath(Map<String, Object> uf, String path) {
        if (uf.containsKey(path)) {
            return uf.get(path);
        }
        if (path.startsWith("userFields.")) {
            return uf.get(path.substring("userFields.".length()));
        }
        String[] parts = path.split("\\.");
        Object cur = uf;
        for (String p : parts) {
            if (!(cur instanceof Map<?, ?> map)) {
                return null;
            }
            cur = map.get(p);
        }
        return cur;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> userFieldsOf(Map<String, Object> inputs) {
        Object uf = inputs.get("userFields");
        if (uf instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return new LinkedHashMap<>(inputs);
    }

    private static String queryOf(Map<String, Object> inputs, Map<String, Object> userFields) {
        Object q = inputs.get("query");
        if (q != null) {
            return String.valueOf(q);
        }
        Object uq = userFields.get("query");
        return uq == null ? "" : String.valueOf(uq);
    }

    private static boolean matchesKeyword(String query, List<String> keywords) {
        if (query == null) {
            return false;
        }
        String t = query.trim().toLowerCase();
        for (String k : keywords) {
            if (t.equals(k.toLowerCase()) || t.contains(k.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : o != null && Boolean.parseBoolean(String.valueOf(o));
    }

    private record RailsResult(Map<String, Object> arguments, List<String> failedFields) {}
}
