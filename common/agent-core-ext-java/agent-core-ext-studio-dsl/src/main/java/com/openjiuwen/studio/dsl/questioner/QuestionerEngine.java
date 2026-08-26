/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.context.ContextWindow;
import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
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

/**
 * Questioner invoke engine — strict 1:1 with Python
 * {@code agent_runtime...questioner.Questioner} / {@code QuestionerDirectReplyHandler}.
 *
 * @since 2026-08-25
 */
public final class QuestionerEngine {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TEMPLATE_VAR = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*\\}\\}");

    private final String nodeId;
    private final QuestionerConfig config;
    private final QuestionerLlmExtractor.ModelInvoker modelInvoker;

    /**
     * QuestionerEngine.
     *
     * @param nodeId nodeId
     * @param config config
     */
    public QuestionerEngine(String nodeId, QuestionerConfig config) {
        this(nodeId, config, null);
    }

    /**
     * QuestionerEngine with injectable LLM (tests).
     *
     * @param nodeId nodeId
     * @param config config
     * @param modelInvoker modelInvoker
     */
    public QuestionerEngine(String nodeId, QuestionerConfig config, QuestionerLlmExtractor.ModelInvoker modelInvoker) {
        this.nodeId = nodeId;
        this.config = config;
        this.modelInvoker = modelInvoker;
    }

    /**
     * invoke.
     *
     * @param inputs inputs
     * @param session session (nullable)
     * @return userFields map (already mapped names when finished); may still be interacting
     */
    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session) {
        return invoke(inputs, session, null);
    }

    /**
     * invoke with model context (Python {@code context} for chat history).
     *
     * @param inputs inputs
     * @param session session
     * @param context model context
     * @return userFields
     */
    public Map<String, Object> invoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
        assertResponseType();
        Map<String, Object> in = inputs == null ? Map.of() : inputs;
        Map<String, Object> userFields = userFieldsOf(in);
        mergeContextChatHistory(userFields, context);

        if (userFields.containsKey("answer")
                || userFields.containsKey("userAnswer")
                || userFields.containsKey("USER_RESPONSE")) {
            return finishFromExisting(userFields);
        }

        QuestionerState state = loadState(session, in);
        state.setInputs(in);
        boolean resuming = state.isUndergoingInteraction() || bool(in.get("__single_debug_recovery__"));
        syncTraceRedis(session, resuming);

        String query = queryOf(in, userFields);
        traceUser(session, query);

        Map<String, Object> result;
        if (!resuming) {
            result = handleStart(userFields, in, session, context, state);
        } else {
            if (!state.isUndergoingInteraction()) {
                state.setStatus(QuestionerState.USER_INTERACT);
            }
            result = handleUserInteract(state, userFields, in, session, context);
        }

        QuestionerState current = stateFromResult(result, state);

        if (QuestionerState.USER_INTERACT.equals(current.status())) {
            storeState(session, current);
            publishInterrupt(session, current.question());
            collectViaInteract(session, current);
            if (session != null) {
                Object reply = tryLatestReply(session, current.question());
                if (reply != null) {
                    Map<String, Object> resumeIn = new LinkedHashMap<>(in);
                    resumeIn.put("query", reply);
                    resumeIn.put("__single_debug_recovery__", true);
                    current.incrementResponseNum();
                    result = handleUserInteract(current, userFields, resumeIn, session, context);
                    current = stateFromResult(result, current);
                    if (!QuestionerState.USER_INTERACT.equals(current.status())) {
                        storeState(session, new QuestionerState());
                        deleteTraceRedis(session);
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
        deleteTraceRedis(session);
        return convertOutputNames(result);
    }

    private void assertResponseType() {
        Object raw =
                config.rawConfigs()
                        .getOrDefault("responseType", config.rawConfigs().get("response_type"));
        if (raw == null) {
            return;
        }
        String type = String.valueOf(raw).trim();
        if (type.isEmpty() || "reply_directly".equals(type)) {
            return;
        }
        throw new NodeExecutionException(
                nodeId,
                "jiuwen.questioner",
                NodeCauseCode.NODE_CONFIG_INVALID,
                "unsupported response_type for agent_runtime questioner: " + type);
    }

    private Map<String, Object> handleStart(
            Map<String, Object> userFields,
            Map<String, Object> inputs,
            NodeSessionApi session,
            ModelContext context,
            QuestionerState unused) {
        String query = queryOf(inputs, userFields);
        writeUserToContext(context, query);

        if (config.hasQuestionContent()) {
            String question = formatTemplate(config.questionContent(), userFields);
            QuestionerState state = new QuestionerState();
            state.setStatus(QuestionerState.USER_INTERACT);
            state.setQuestion(question);
            writeAssistantToContext(context, question);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("question", question);
            out.put("_state", state.toMap());
            traceAssistant(session, question);
            return out;
        }
        if (config.needExtractFields()) {
            return runExtractCycle(new QuestionerState(), query, userFields, inputs, session, context);
        }
        throw new NodeExecutionException(
                nodeId,
                "jiuwen.questioner",
                NodeCauseCode.NODE_CONFIG_INVALID,
                "question_content is empty and no extractable fields are configured");
    }

    private Map<String, Object> handleUserInteract(
            QuestionerState state,
            Map<String, Object> userFields,
            Map<String, Object> inputs,
            NodeSessionApi session,
            ModelContext context) {
        String query = queryOf(inputs, userFields);
        if (bool(inputs.get("__single_debug_recovery__"))) {
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

        writeUserToContext(context, query);

        if (config.allowNodeBreak() && QuestionerKeywords.matchesBreak(query)) {
            state.setUserBreak(true);
            state.setStatus(QuestionerState.END);
            Map<String, Object> out = new LinkedHashMap<>(state.extractedFields());
            out.put("user_response", query);
            out.put("question", QuestionerKeywords.MSG_BREAK);
            out.put("status", "break");
            out.put("_state", state.toMap());
            return out;
        }
        if (config.allowNodeConfirm()
                && state.needUserConfirm()
                && QuestionerKeywords.matchesConfirm(query)) {
            state.setNeedUserConfirm(false);
            state.setStatus(QuestionerState.END);
            Map<String, Object> out = new LinkedHashMap<>(state.extractedFields());
            out.put("user_response", query);
            out.put("question", QuestionerKeywords.MSG_CONFIRMED);
            out.put("status", "confirmed");
            out.put("_state", state.toMap());
            return out;
        }

        if (config.hasQuestionContent() && !config.needExtractFields(state)) {
            state.setStatus(QuestionerState.END);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("user_response", query);
            out.put("question", state.question());
            out.put("status", "end");
            out.put("_state", state.toMap());
            return out;
        }

        return runExtractCycle(state, query, userFields, inputs, session, context);
    }

    private Map<String, Object> runExtractCycle(
            QuestionerState state,
            String query,
            Map<String, Object> userFields,
            Map<String, Object> inputs,
            NodeSessionApi session,
            ModelContext context) {
        Map<String, Object> extracted = extractFields(query, userFields, state, session);
        Map<String, Object> merged = new LinkedHashMap<>(state.extractedFields());
        extracted.forEach((k, v) -> {
            if (v != null && !"".equals(v) && !"null".equalsIgnoreCase(String.valueOf(v))) {
                merged.put(k, v);
            }
        });
        applyDefaults(merged);
        RailsResult rails = applyRails(merged, query, state, inputs);
        state.extractedFields().clear();
        state.extractedFields().putAll(rails.arguments());
        state.fieldsCheckFailed().clear();
        state.fieldsCheckFailed().addAll(rails.failedFields());

        ContinueAskDecision decision = checkIfContinueAsk(state);
        Map<String, Object> out = new LinkedHashMap<>();
        if (decision.continueAsk()) {
            state.setStatus(QuestionerState.USER_INTERACT);
            state.setQuestion(decision.question());
            state.setNeedUserConfirm(decision.needUserConfirm());
            out.put("question", decision.question());
            writeAssistantToContext(context, decision.question());
            traceAssistant(session, decision.question());
        } else {
            state.setStatus(QuestionerState.END);
            out.put("status", "end");
            out.put("question", state.question());
            Map<String, Object> finalFields = finalKeyFields(state);
            out.putAll(finalFields);
            writeAssistantToContext(context, jsonFields(finalFields));
        }
        if (query != null && !query.isBlank()) {
            out.put("user_response", query);
        }
        out.put("_state", state.toMap());
        return out;
    }

    private ContinueAskDecision checkIfContinueAsk(QuestionerState state) {
        List<QuestionerField> missing = nonExtracted(state);
        if (!missing.isEmpty()) {
            if (state.responseNum() >= config.maxResponse()) {
                state.fieldsCheckFailed().clear();
                return ContinueAskDecision.end();
            }
            String q = QuestionerRailsHints.constructContinueQuestion(config, missing);
            state.setNeedUserConfirm(false);
            return ContinueAskDecision.ask(q, false);
        }
        if (config.allowNodeConfirm()) {
            String q = QuestionerRailsHints.constructConfirmationQuestion(config, state.extractedFields());
            state.setNeedUserConfirm(true);
            return ContinueAskDecision.ask(q, true);
        }
        return ContinueAskDecision.end();
    }

    private static Map<String, Object> finalKeyFields(QuestionerState state) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : state.extractedFields().entrySet()) {
            if (state.fieldsCheckFailed().contains(e.getKey())) {
                out.put(e.getKey(), "");
            } else {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private static String jsonFields(Map<String, Object> fields) {
        try {
            return MAPPER.writeValueAsString(fields);
        } catch (Exception e) {
            return String.valueOf(fields);
        }
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

    private Map<String, Object> extractFields(
            String query, Map<String, Object> userFields, QuestionerState state, NodeSessionApi session) {
        if (!config.mockExtractedFields().isEmpty()) {
            return new LinkedHashMap<>(config.mockExtractedFields());
        }
        if (config.hasModelWiring() || modelInvoker != null) {
            QuestionerLlmExtractor extractor =
                    new QuestionerLlmExtractor(nodeId, config, modelInvoker);
            List<Map<String, String>> history = chatHistoryFrom(userFields, query);
            return extractor.extract(query, history, state, session);
        }
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            try {
                Map<String, Object> parsed = QuestionerLlmExtractor.parseJsonObject(trimmed);
                if (!parsed.isEmpty()) {
                    return filterKnown(parsed);
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        List<QuestionerField> required =
                config.keyFields().stream().filter(QuestionerField::required).toList();
        if (required.size() == 1 && !trimmed.isEmpty()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put(required.get(0).fieldName(), convertType(trimmed, required.get(0).type()));
            return one;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (QuestionerField f : config.keyFields()) {
            if (userFields.containsKey(f.fieldName())) {
                out.put(f.fieldName(), convertType(userFields.get(f.fieldName()), f.type()));
            }
        }
        return out;
    }

    private static List<Map<String, String>> chatHistoryFrom(Map<String, Object> userFields, String query) {
        List<Map<String, String>> history = new ArrayList<>();
        Object raw = userFields.get("chatHistory");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, String> turn = new LinkedHashMap<>();
                    Object role = m.get("role");
                    Object content = m.get("content");
                    turn.put("role", role == null ? "user" : String.valueOf(role));
                    turn.put("content", content == null ? "" : String.valueOf(content));
                    history.add(turn);
                }
            }
        }
        if (history.isEmpty()
                || "assistant".equalsIgnoreCase(history.get(history.size() - 1).get("role"))) {
            if (query != null && !query.isBlank()) {
                Map<String, String> turn = new LinkedHashMap<>();
                turn.put("role", "user");
                turn.put("content", query);
                history.add(turn);
            }
        }
        return history;
    }

    private void mergeContextChatHistory(Map<String, Object> userFields, ModelContext context) {
        if (!config.withChatHistory() || context == null) {
            return;
        }
        if (userFields.containsKey("chatHistory")) {
            return;
        }
        try {
            Integer dialogueRound = chatHistoryMaxRounds();
            ContextWindow window = context.getContextWindow(null, null, null, dialogueRound);
            List<BaseMessage> messages =
                    window != null && window.getMessages() != null
                            ? window.getMessages()
                            : context.getMessages();
            if (messages == null || messages.isEmpty()) {
                return;
            }
            List<Map<String, String>> history = new ArrayList<>();
            for (BaseMessage msg : messages) {
                if (msg == null) {
                    continue;
                }
                Map<String, String> turn = new LinkedHashMap<>();
                String role = msg.getRole();
                if (role == null || role.isBlank()) {
                    String simple = msg.getClass().getSimpleName().toLowerCase();
                    if (simple.contains("system")) {
                        role = "system";
                    } else if (simple.contains("assistant")) {
                        role = "assistant";
                    } else {
                        role = "user";
                    }
                }
                turn.put("role", role);
                turn.put("content", msg.getContent() == null ? "" : String.valueOf(msg.getContent()));
                history.add(turn);
            }
            if (!history.isEmpty()) {
                userFields.put("chatHistory", history);
            }
        } catch (RuntimeException ignored) {
            // soft-fail
        }
    }

    private void writeUserToContext(ModelContext context, String content) {
        if (!config.withChatHistory() || context == null || content == null || content.isBlank()) {
            return;
        }
        try {
            context.addMessages(new UserMessage(content));
        } catch (RuntimeException ignored) {
            // soft-fail
        }
    }

    private void writeAssistantToContext(ModelContext context, String content) {
        if (!config.withChatHistory() || context == null || content == null || content.isBlank()) {
            return;
        }
        try {
            context.addMessages(new AssistantMessage(content));
        } catch (RuntimeException ignored) {
            // soft-fail
        }
    }

    private Integer chatHistoryMaxRounds() {
        Object raw =
                config.rawConfigs()
                        .getOrDefault(
                                "chatHistoryMaxRounds",
                                config.rawConfigs().get("chat_history_max_rounds"));
        int n = 5;
        if (raw instanceof Number num) {
            n = num.intValue();
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            try {
                n = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                n = 5;
            }
        }
        return n > 0 ? n : null;
    }

    private void syncTraceRedis(NodeSessionApi session, boolean resuming) {
        String sessionId = sessionIdOf(session);
        if (sessionId == null) {
            return;
        }
        if (resuming) {
            QuestionerTraceStore.recoverToSession(sessionId, nodeId, session);
        } else {
            QuestionerTraceStore.delete(sessionId, nodeId);
        }
    }

    private void deleteTraceRedis(NodeSessionApi session) {
        String sessionId = sessionIdOf(session);
        if (sessionId != null) {
            QuestionerTraceStore.delete(sessionId, nodeId);
        }
    }

    private void traceUser(NodeSessionApi session, String user) {
        Map<String, Object> userTrace = Map.of("user", user == null ? "" : user);
        softTrace(session, userTrace);
        appendRedisTrace(session, userTrace);
    }

    private void traceAssistant(NodeSessionApi session, String assistant) {
        Map<String, Object> asstTrace = Map.of("assistant", assistant == null ? "" : assistant);
        softTrace(session, asstTrace);
        appendRedisTrace(session, asstTrace);
    }

    private void softTrace(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
            return;
        }
        try {
            session.trace(data);
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private void appendRedisTrace(NodeSessionApi session, Map<String, Object> data) {
        String sessionId = sessionIdOf(session);
        if (sessionId == null) {
            return;
        }
        QuestionerTraceStore.append(sessionId, nodeId, data);
    }

    private static String sessionIdOf(NodeSessionApi session) {
        if (session == null) {
            return null;
        }
        try {
            String id = session.getSessionId();
            return id == null || id.isBlank() ? null : id;
        } catch (RuntimeException e) {
            return null;
        }
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
            if ((v == null || "".equals(v)) && f.defaultValue() != null && !"".equals(f.defaultValue())) {
                fields.put(f.fieldName(), f.defaultValue());
            }
        }
    }

    private RailsResult applyRails(
            Map<String, Object> fields, String userInput, QuestionerState state, Map<String, Object> inputs) {
        Map<String, Object> before = new LinkedHashMap<>(fields);
        if (config.railsConfig() == null || config.railsConfig().isEmpty()) {
            return new RailsResult(before, List.of());
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("arguments", before);
        ctx.put("inputs", state.inputs().isEmpty() ? inputs : state.inputs());
        ctx.put("outputs", QuestionerRailsHints.fieldOutputsForRails(config));
        ctx.put("extracted_args", new LinkedHashMap<>(state.extractedFields()));
        ctx.put("user_input", userInput == null ? "" : userInput);
        Map<String, Object> after = RailsRegistry.executeRails(config.railsConfig(), ctx);
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Object> e : before.entrySet()) {
            Object b = e.getValue();
            Object a = after.get(e.getKey());
            if (b != null && !"".equals(b) && a == null) {
                failed.add(e.getKey());
                after.put(e.getKey(), null);
            }
        }
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
            String key =
                    switch (e.getKey()) {
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

    private static boolean bool(Object o) {
        return o instanceof Boolean b ? b : o != null && Boolean.parseBoolean(String.valueOf(o));
    }

    private record RailsResult(Map<String, Object> arguments, List<String> failedFields) {}

    private record ContinueAskDecision(boolean continueAsk, String question, boolean needUserConfirm) {
        static ContinueAskDecision end() {
            return new ContinueAskDecision(false, "", false);
        }

        static ContinueAskDecision ask(String question, boolean needUserConfirm) {
            return new ContinueAskDecision(true, question, needUserConfirm);
        }
    }
}
