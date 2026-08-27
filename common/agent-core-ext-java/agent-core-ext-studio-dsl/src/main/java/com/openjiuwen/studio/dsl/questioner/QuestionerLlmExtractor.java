/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.component.llm.QuestionerDefaultConfig;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.rails.formatters.DateUtilCompatibleParser;
import com.openjiuwen.studio.dsl.rails.formatters.DateTimeFormatValidateAction;
import com.openjiuwen.studio.dsl.util.TypeCoercer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM field extraction + reflection for Questioner
 * (Python {@code QuestionerDirectReplyHandler._invoke_llm_for_extraction} / {@code _reflection}).
 *
 * @since 2026-08-26
 */
public final class QuestionerLlmExtractor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Pattern JSON_FENCE =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);
    private static final String REFLECTION_ZH =
            """
            你是一个信息验证助手。请验证以下提取的字段值是否正确。

            字段名称：{{field_name}}
            提取的值：{{response}}

            请仔细检查这个值是否合理、格式是否正确。如果需要修正，请返回修正后的值。
            返回格式：{"{{field_name}}": "修正后的值"} 或 {"{{field_name}}": "原值"} 如果值正确。
            """;
    private static final String REFLECTION_EN =
            """
            You are an information verification assistant. Please verify if the following extracted field value is correct.

            Field name: {{field_name}}
            Extracted value: {{response}}

            Please carefully check if this value is reasonable and if the format is correct. If correction is needed, return the corrected value.
            Return format: {"{{field_name}}": "corrected value"} or {"{{field_name}}": "original value"} if the value is correct.
            """;

    /** Injectable for tests (returns raw LLM text). */
    @FunctionalInterface
    public interface ModelInvoker {
        String invoke(List<BaseMessage> messages) throws Exception;
    }

    private final String nodeId;
    private final QuestionerConfig config;
    private final ModelInvoker invoker;

    public QuestionerLlmExtractor(String nodeId, QuestionerConfig config) {
        this(nodeId, config, null);
    }

    public QuestionerLlmExtractor(String nodeId, QuestionerConfig config, ModelInvoker invoker) {
        this.nodeId = nodeId;
        this.config = config;
        this.invoker = invoker != null ? invoker : defaultInvoker(config);
    }

    public static boolean hasModelWiring(Map<String, Object> configs) {
        if (configs == null) {
            return false;
        }
        if (configs.get("apiKey") != null || configs.get("modelClientConfig") instanceof Map<?, ?>) {
            return true;
        }
        Object model = configs.getOrDefault("modelId", configs.getOrDefault("model", configs.get("modelName")));
        return model != null && !String.valueOf(model).isBlank();
    }

    public Map<String, Object> extract(
            String query, List<Map<String, String>> chatHistory, QuestionerState state, NodeSessionApi session) {
        List<BaseMessage> llmInputs = buildExtractionMessages(query, chatHistory);
        String response;
        try {
            response = invoker.invoke(llmInputs);
        } catch (Exception e) {
            throw new NodeExecutionException(
                    nodeId,
                    "jiuwen.questioner",
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "failed to invoke llm for extraction: " + e.getMessage(),
                    e);
        }
        traceLlm(session, llmInputs, response);
        Map<String, Object> result = parseJsonObject(response);
        // Python: _try_parse_time_from_user_input before filtering nulls away
        result = tryParseTimeFromUserInput(result, query);
        result = filterValid(result);
        result = reflect(result, state, session);
        return validateAndConvert(result);
    }

    /**
     * Python {@code _try_parse_time_from_user_input} — when LLM left a field null and rails
     * declare {@code date_time_format}, parse the raw user query into that format.
     */
    Map<String, Object> tryParseTimeFromUserInput(Map<String, Object> extracted, String userInput) {
        if (extracted == null) {
            extracted = new LinkedHashMap<>();
        } else {
            extracted = new LinkedHashMap<>(extracted);
        }
        if (userInput == null || userInput.isBlank()) {
            return extracted;
        }
        for (QuestionerField field : config.keyFields()) {
            Object cur = extracted.get(field.fieldName());
            if (cur != null && isValidValue(cur)) {
                continue;
            }
            String dateFormat = QuestionerRailsHints.dateTimeFormatConstraint(config, field.fieldName());
            if (dateFormat == null || dateFormat.isBlank()) {
                continue;
            }
            java.time.LocalDateTime parsed = DateUtilCompatibleParser.tryParse(userInput.trim());
            if (parsed != null) {
                extracted.put(
                        field.fieldName(),
                        DateUtilCompatibleParser.formatWithPyPattern(parsed, dateFormat));
            }
        }
        return extracted;
    }

    private List<BaseMessage> buildExtractionMessages(String query, List<Map<String, String>> chatHistory) {
        List<BaseMessage> template = QuestionerDefaultConfig.getDefaultTemplate(config.acceptLanguage());
        Map<String, String> keywords = createPromptKeywords(query, chatHistory);
        List<BaseMessage> out = new ArrayList<>();
        for (BaseMessage msg : template) {
            String content = msg.getContent() == null ? "" : String.valueOf(msg.getContent());
            content = replacePlaceholders(content, keywords);
            if (msg instanceof SystemMessage) {
                out.add(new SystemMessage(content));
            } else {
                out.add(new UserMessage(content));
            }
        }
        if (config.promptTemplate() != null && !config.promptTemplate().isBlank()) {
            out.clear();
            out.add(new SystemMessage(replacePlaceholders(config.promptTemplate(), keywords)));
            out.add(new UserMessage(keywords.getOrDefault("dialogue_history", "")));
        }
        return out;
    }

    private Map<String, String> createPromptKeywords(String query, List<Map<String, String>> chatHistory) {
        List<String> requiredNames = new ArrayList<>();
        List<String> paramsList = new ArrayList<>();
        List<String> allConstraints = new ArrayList<>();
        for (QuestionerField f : config.keyFields()) {
            String paramDesc = f.fieldName() + ": " + f.description();
            String constraintStr = QuestionerRailsHints.formatConstraintsForPrompt(config, f.fieldName());
            if (!constraintStr.isBlank()) {
                paramDesc += " " + constraintStr;
                allConstraints.add(f.fieldName() + ": " + constraintStr.strip());
            }
            paramsList.add(paramDesc);
            if (f.required()) {
                requiredNames.add(f.cnFieldName().isBlank() ? f.description() : f.cnFieldName());
            }
        }
        boolean en = "en".equalsIgnoreCase(config.acceptLanguage());
        String requiredName =
                en
                        ? String.join(", ", requiredNames) + " (" + requiredNames.size() + " required field(s))"
                        : String.join("、", requiredNames) + requiredNames.size() + "个必要信息";
        StringBuilder history = new StringBuilder();
        if (chatHistory != null) {
            for (Map<String, String> turn : chatHistory) {
                String role = turn.getOrDefault("role", "user");
                String content = turn.getOrDefault("content", "");
                if (en) {
                    history.append(role).append(": ").append(content).append('\n');
                } else {
                    history.append(role).append("：").append(content).append('\n');
                }
            }
        }
        if (query != null && !query.isBlank()) {
            if (en) {
                history.append("user: ").append(query);
            } else {
                history.append("user：").append(query);
            }
        }
        Map<String, String> keywords = new LinkedHashMap<>();
        keywords.put("required_name", requiredName);
        keywords.put("required_params_list", String.join("\n", paramsList));
        String extra = config.extraPromptForFieldsExtraction();
        if (!allConstraints.isEmpty()) {
            String note =
                    en
                            ? "Note: The following fields have constraints - "
                                    + String.join(", ", allConstraints)
                            : "注意：以下字段有约束限制 - " + String.join(", ", allConstraints);
            extra = extra == null || extra.isBlank() ? note : note + "\n" + extra;
        }
        keywords.put("extra_info", extra == null ? "" : extra);
        keywords.put("example", config.exampleContent());
        keywords.put("dialogue_history", history.toString());
        return keywords;
    }

    private Map<String, Object> reflect(Map<String, Object> extracted, QuestionerState state, NodeSessionApi session) {
        if (extracted == null || extracted.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cur = new LinkedHashMap<>(extracted);
        String tmpl = "en".equalsIgnoreCase(config.acceptLanguage()) ? REFLECTION_EN : REFLECTION_ZH;
        for (QuestionerField field : config.keyFields()) {
            Object value = cur.get(field.fieldName());
            if (value == null || "".equals(value) || !field.reflection()) {
                continue;
            }
            Object prev = state.reflectionMap().get(field.fieldName());
            if (prev != null && String.valueOf(prev).equals(String.valueOf(value))) {
                continue;
            }
            try {
                Map<String, String> ph = new LinkedHashMap<>();
                ph.put("field_name", field.fieldName());
                ph.put("response", String.valueOf(Map.of(field.fieldName(), value)));
                String content = replacePlaceholders(tmpl, ph);
                List<BaseMessage> msgs = List.of(new SystemMessage(content));
                String raw = invoker.invoke(msgs);
                Map<String, Object> parsed = parseJsonObject(raw);
                if (parsed.containsKey(field.fieldName())) {
                    Object nv = parsed.get(field.fieldName());
                    cur.put(field.fieldName(), nv);
                    state.reflectionMap().put(field.fieldName(), nv);
                }
            } catch (Exception ignored) {
                // Python soft-fails reflection
            }
        }
        return cur;
    }

    private Map<String, Object> validateAndConvert(Map<String, Object> extracted) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (QuestionerField f : config.keyFields()) {
            if (!extracted.containsKey(f.fieldName())) {
                continue;
            }
            Object v = extracted.get(f.fieldName());
            if (!isValidValue(v)) {
                continue;
            }
            out.put(f.fieldName(), TypeCoercer.coerce(v, f.type(), null, false));
        }
        return out;
    }

    public static Map<String, Object> parseJsonObject(String response) {
        if (response == null || response.isBlank()) {
            return Map.of();
        }
        String cleaned = extractJsonFromText(response);
        try {
            Object parsed = MAPPER.readValue(cleaned, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignored) {
            // fall through
        }
        Map<String, Object> literal = tryPythonLiteralMap(cleaned);
        return literal == null ? Map.of() : literal;
    }

    static Map<String, Object> tryPythonLiteralMap(String cleaned) {
        if (cleaned == null || cleaned.isBlank() || !cleaned.strip().startsWith("{")) {
            return null;
        }
        try {
            String jsonish =
                    cleaned.strip()
                            .replace('\'', '"')
                            .replaceAll("\\bNone\\b", "null")
                            .replaceAll("\\bTrue\\b", "true")
                            .replaceAll("\\bFalse\\b", "false");
            Object parsed = MAPPER.readValue(jsonish, Object.class);
            if (parsed instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return out;
            }
        } catch (Exception ignored) {
            // ast.literal_eval parity best-effort
        }
        return null;
    }

    static String extractJsonFromText(String text) {
        String t = text.strip();
        Matcher fence = JSON_FENCE.matcher(t);
        if (fence.find()) {
            return fence.group(1).strip();
        }
        int start = t.indexOf('{');
        int end = t.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return t.substring(start, end + 1);
        }
        return t;
    }

    private static Map<String, Object> filterValid(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (isValidValue(v)) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static boolean isValidValue(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof String s) {
            String t = s.strip().toLowerCase();
            return !t.isEmpty() && !"null".equals(t) && !"none".equals(t);
        }
        if (v instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        if (v instanceof List<?> list) {
            return !list.isEmpty();
        }
        return true;
    }

    private static String replacePlaceholders(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static List<Map<String, Object>> messageMaps(List<BaseMessage> msgs) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (msgs == null) {
            return out;
        }
        for (BaseMessage m : msgs) {
            if (m == null) {
                continue;
            }
            Map<String, Object> turn = new LinkedHashMap<>();
            String role = m.getRole();
            if (role == null || role.isBlank()) {
                String simple = m.getClass().getSimpleName().toLowerCase();
                if (simple.contains("system")) {
                    role = "system";
                } else if (simple.contains("assistant")) {
                    role = "assistant";
                } else {
                    role = "user";
                }
            }
            turn.put("role", role);
            turn.put("content", m.getContent() == null ? "" : String.valueOf(m.getContent()));
            out.add(turn);
        }
        return out;
    }

    private void traceLlm(NodeSessionApi session, List<BaseMessage> inputs, String response) {
        if (session == null || response == null) {
            return;
        }
        Map<String, Object> llmInfo = new LinkedHashMap<>();
        llmInfo.put("llm_inputs", messageMaps(inputs));
        llmInfo.put("llm_outputs", response);
        Map<String, Object> traceData = Map.of("llm_info", llmInfo);
        trace(session, traceData);
        appendRedisTrace(session, traceData);
    }

    private void appendRedisTrace(NodeSessionApi session, Map<String, Object> data) {
        if (session == null || data == null || data.isEmpty()) {
            return;
        }
        try {
            String sessionId = session.getSessionId();
            if (sessionId == null || sessionId.isBlank()) {
                return;
            }
            QuestionerTraceStore.append(sessionId, nodeId, data);
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private static void trace(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
            return;
        }
        try {
            session.trace(data);
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private static ModelInvoker defaultInvoker(QuestionerConfig config) {
        return messages -> {
            ModelClientConfig client = config.modelClientConfig();
            ModelRequestConfig request = config.modelRequestConfig();
            if (client == null || request == null) {
                throw new IllegalStateException("questioner model client/request config missing");
            }
            Model model = new Model(client, request);
            AssistantMessage msg = model.invoke(messages, null, null, null, null, null, null, null, null, null);
            return msg.getContent() == null ? "" : String.valueOf(msg.getContent());
        };
    }
}
