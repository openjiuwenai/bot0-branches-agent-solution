/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.intentdetection;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.NodeCauseCode;
import com.openjiuwen.studio.dsl.questioner.QuestionerLlmExtractor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LLM intent classification (Python {@code get_llm_result} / {@code intent_detection_post_process}).
 *
 * @since 2026-08-26
 */

public final class IntentDetectionLlmDetector {
    private static final String DEFAULT_SYSTEM_PROMPT = "你是一个识别用户输入意图的AI助手。";
    private static final String DEFAULT_USER_PROMPT =
            """
            {{user_prompt}}

            当前可供选择的功能分类如下：
            {{category_info}}

            用户与助手的对话历史：
            {{chat_history}}

            当前输入：
            {{input}}

            请根据当前输入和对话历史分析并输出最适合的功能分类。输出格式为 JSON，包含以下两个字段：
            class: 代表分类结果
            reason: 说明为何选择该分类
            例如: {{"class": "分类xx", "reason": "当前输入xxx"}}
            请参考以下示例：
            {{example_content}}
            如果没有合适的分类，请输出 {{default_class}}。
            """;

    private static final Map<String, String> ROLE_MAP =
            Map.of("user", "用户", "assistant", "助手", "system", "系统");

    private static final String JSON_PARSE_FAIL_REASON =
            "当前意图识别的输出:'%s'格式不符合有效的JSON规范，导致解析失败，因此返回默认分类。";
    private static final String CLASS_KEY_MISSING_REASON =
            "当前意图识别的输出 '%s' 缺少必要的输出'class'分类信息，因此返回默认分类。";
    static final String VALIDATION_FAIL_REASON =
            "当前意图识别的输出类别 '%s' 不在预定义的分类列表: '%s'中，因此系统返回默认分类。";

    private static final String MEMORY_MESSAGE = "memory_message";
    private static final String USER_PROFILE_KEY = "userProfile";
    private static final String ENABLE_KEY = "enable";

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{[^{}]*(?:\\{[^{}]*}[^{}]*)*}", Pattern.DOTALL);

    @FunctionalInterface
    public interface ModelInvoker {
        String invoke(List<BaseMessage> messages) throws Exception;
    }

    /**
     * DetectionResult.
     *
     * @param intentClass intentClass
     * @param reason reason
     * @param classificationId classificationId
     * @param name name
     * @return result
     * @since 0.1.0
     */

    public record DetectionResult(String intentClass, String reason, int classificationId, String name) {}

    private final String nodeId;
    private final IntentDetectionConfig config;
    private final ModelInvoker invoker;

    /**
     * IntentDetectionLlmDetector.
     *
     * @param nodeId nodeId
     * @param config config
     * @since 0.1.0
     */

    public IntentDetectionLlmDetector(String nodeId, IntentDetectionConfig config) {
        this(nodeId, config, null, Map.of());
    }

    /**
     * IntentDetectionLlmDetector.
     *
     * @param nodeId nodeId
     * @param config config
     * @param invoker invoker
     * @since 0.1.0
     */

    public IntentDetectionLlmDetector(String nodeId, IntentDetectionConfig config, ModelInvoker invoker) {
        this(nodeId, config, invoker, Map.of());
    }

    /**
     * IntentDetectionLlmDetector.
     *
     * @param nodeId nodeId
     * @param config config
     * @param invoker invoker
     * @param modelMap modelMap
     * @since 0.1.0
     */

    public IntentDetectionLlmDetector(
            String nodeId, IntentDetectionConfig config, ModelInvoker invoker, Map<String, Object> modelMap) {
        this.nodeId = nodeId;
        this.config = config;
        this.invoker = invoker != null ? invoker : defaultInvoker(config, modelMap);
    }

    /**
     * hasModelWiring.
     *
     * @param configs configs
     * @return result
     * @since 0.1.0
     */

    public static boolean hasModelWiring(Map<String, Object> configs) {
        if (configs == null) {
        return false;
    }
        Object llm = configs.get("llm");
        if (llm instanceof Map<?, ?> lm) {
            Object model = lm.get("model");
            if (model instanceof Map<?, ?> m) {
                Object name = first(m, "modelName", "model_name");
                Object type = first(m, "modelType", "model_type");
                if (name != null
                        && !String.valueOf(name).isBlank()
                        && type != null
                        && !String.valueOf(type).isBlank()) {
                    return true;
                }
            }
        }
        return QuestionerLlmExtractor.hasModelWiring(configs);
    }

    /**
     * Python {@code get_llm_result}.
     *
     * @param input input
     * @param chatHistory chatHistory
     * @param categoryInfo categoryInfo
     * @param exampleContent exampleContent
     * @param memConf memConf
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public String invokeLlm(
            String input,
            String chatHistory,
            String categoryInfo,
            String exampleContent,
            Map<String, Object> memConf,
            NodeSessionApi session) {
        Map<String, String> templateInput = new LinkedHashMap<>();
        templateInput.put("user_prompt", config.userPrompt());
        templateInput.put("category_info", categoryInfo == null ? config.categoryInfo() : categoryInfo);
        templateInput.put("chat_history", chatHistory == null ? "" : chatHistory);
        templateInput.put("input", input == null ? "" : input);
        templateInput.put("example_content", exampleContent == null ? "" : exampleContent);
        templateInput.put("default_class", config.defaultClass());

        List<BaseMessage> messages = buildMessages(templateInput);
        appendMemoryMessage(messages, memConf, session);

        Map<String, Object> tracePayload = new LinkedHashMap<>(templateInput);
        tracePayload.put("llm_inputs", summarizeMessages(messages));
        trace(session, tracePayload);

        try {
            String llmOutput = invoker.invoke(messages);
            trace(session, Map.of("llm_outputs", llmOutput == null ? "" : llmOutput));
            return llmOutput == null ? "" : llmOutput;
        } catch (Exception e) {
            throw new NodeExecutionException(
                    nodeId,
                    "jiuwen.intentDetection",
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "intent detection llm invoke error, reason: " + e.getClass().getSimpleName(),
                    e);
        }
    }

    /**
     * Unit-test helper around {@link #invokeLlm} + post-process.
     *
     * @param input input
     * @param chatHistory chatHistory
     * @param categoryInfo categoryInfo
     * @param faqFewShotExample faqFewShotExample
     * @param globalIntentMap globalIntentMap
     * @param session session
     * @return result
     * @since 0.1.0
     */

    public DetectionResult detect(
            String input,
            String chatHistory,
            String categoryInfo,
            String faqFewShotExample,
            Map<String, Object> globalIntentMap,
            NodeSessionApi session) {
        String example = String.join("\n\n", config.exampleContent());
        if (faqFewShotExample != null && !faqFewShotExample.isBlank()) {
            example = example.isBlank() ? faqFewShotExample : example + faqFewShotExample;
        }
        String llmOutput = invokeLlm(input, chatHistory, categoryInfo, example, Map.of(), session);
        return toDetectionResult(llmOutput, globalIntentMap);
    }

    private DetectionResult toDetectionResult(String llmOutput, Map<String, Object> globalIntentMap) {
        String[] parsed = postProcess(config, llmOutput);
        String intentClass = parsed[0];
        String reason = parsed[1];

        if (!config.categoryList().contains(intentClass)) {
            Map<String, Object> idName = intentIdName(config.defaultClass());
            return new DetectionResult(
                    config.defaultClass(),
                    String.format(VALIDATION_FAIL_REASON, intentClass, config.categoryList()),
                    classificationIdOf(idName),
                    nameOf(idName, IntentDetectionConfig.DEFAULT_CLASS_NAME));
        }

        Map<String, Object> idName = intentIdName(intentClass);
        return new DetectionResult(
                intentClass, reason, classificationIdOf(idName), nameOf(idName, ""));
    }

    static String[] postProcess(IntentDetectionConfig config, String result) {
        String raw = refixLlmOutput(result);
        Map<String, Object> parsed = QuestionerLlmExtractor.parseJsonObject(raw);
        if (parsed.isEmpty()) {
            return new String[] {config.defaultClass(), String.format(JSON_PARSE_FAIL_REASON, result)};
        }
        Object cls = parsed.get("class");
        if (cls == null) {
            cls = parsed.get("result");
        }
        if (cls == null || String.valueOf(cls).isBlank()) {
            return new String[] {config.defaultClass(), String.format(CLASS_KEY_MISSING_REASON, parsed)};
        }
        String intentClass = String.valueOf(cls).replace("\n", "").replace(" ", "").replace("'", "").replace("\"", "");
        if (!intentClass.contains("分类")) {
            intentClass = "分类" + intentClass;
        }
        String reason = parsed.get("reason") == null ? "" : String.valueOf(parsed.get("reason"));
        return new String[] {intentClass, reason};
    }

    static String refixLlmOutput(String inputStr) {
        if (inputStr == null) {
        return "";
    }
        Matcher matcher = JSON_OBJECT.matcher(inputStr.strip());
        if (matcher.find()) {
            String res = matcher.group(0);
            if (res.contains("</cot>")) {
                String[] parts = res.split("</cot>");
                res = parts[parts.length - 1];
            }
            return res;
        }
        return inputStr;
    }

    static String formatChatHistory(List<Map<String, Object>> chatHistory, int maxTurn) {
        List<Map<String, Object>> filtered = filterEnableHistory(chatHistory);
        int from = Math.max(0, filtered.size() - maxTurn);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < filtered.size(); i++) {
            Map<String, Object> turn = filtered.get(i);
            String role = ROLE_MAP.getOrDefault(str(turn.get("role")), "用户");
            sb.append(role).append("：").append(str(turn.get("content"))).append('\n');
        }
        return sb.toString();
    }

    static List<Map<String, Object>> filterEnableHistory(List<Map<String, Object>> chatHistory) {
        if (chatHistory == null) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> msg : chatHistory) {
            Object enable = msg.get("enable_history");
            if (enable instanceof Boolean b && !b) {
                continue;
            }
            out.add(msg);
        }
        return out;
    }

    static int classificationIndex(String cls) {
        if (cls == null) {
        return 0;
    }
        String digits = cls.replace("分类", "").replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void appendMemoryMessage(
            List<BaseMessage> messages, Map<String, Object> memConf, NodeSessionApi session) {
        if (memConf == null || session == null) {
        return;
    }
        Object userProfile = memConf.get(USER_PROFILE_KEY);
        if (!(userProfile instanceof Map<?, ?> profile)) {
            return;
        }
        Object enable = profile.get(ENABLE_KEY);
        if (!(enable instanceof Boolean enabled) || !enabled) {
            return;
        }
        try {
            Object memoryMsg = session.getGlobalState(MEMORY_MESSAGE);
            if (memoryMsg instanceof BaseMessage baseMessage) {
                messages.add(baseMessage);
            }
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock session
        }
    }

    private Map<String, Object> intentIdName(String intentClass) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classificationId", classificationIndex(intentClass));
        out.put("name", IntentDetectionConfig.DEFAULT_CLASS_NAME);
        for (int i = 0; i < config.categoryList().size(); i++) {
            if (config.categoryList().get(i).equals(intentClass)) {
                out.put("classificationId", classificationIndex(intentClass));
                if (i < config.categoryNameList().size()) {
                    out.put("name", config.categoryNameList().get(i));
                }
                break;
            }
        }
        return out;
    }

    private static int classificationIdOf(Map<String, Object> idName) {
        Object v = idName.get("classificationId");
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static String nameOf(Map<String, Object> idName, String def) {
        Object v = idName.get("name");
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    private List<BaseMessage> buildMessages(Map<String, String> templateInput) {
        List<BaseMessage> out = new ArrayList<>();
        out.add(new SystemMessage(replacePlaceholders(DEFAULT_SYSTEM_PROMPT, templateInput)));
        out.add(new UserMessage(replacePlaceholders(DEFAULT_USER_PROMPT, templateInput)));
        return out;
    }

    private static String replacePlaceholders(String template, Map<String, String> values) {
        String out = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static List<String> summarizeMessages(List<BaseMessage> msgs) {
        return msgs.stream()
                .map(m -> m.getClass().getSimpleName() + ":" + m.getContent())
                .collect(Collectors.toList());
    }

    private static void trace(NodeSessionApi session, Map<String, Object> data) {
        if (session == null) {
        return;
    }
        try {
            session.trace(data);
        } catch (IllegalStateException | ClassCastException | NullPointerException | IllegalArgumentException | IndexOutOfBoundsException ignored) {
            // mock session
        }
    }

    private static ModelInvoker defaultInvoker(IntentDetectionConfig config, Map<String, Object> modelMap) {
        return messages -> {
            ModelClientConfig client = buildClient(config, modelMap);
            ModelRequestConfig request = buildRequest(config);
            Model model = new Model(client, request);
            AssistantMessage msg = model.invoke(messages, null, null, null, null, null, null, null, null, null);
            return msg.getContent() == null ? "" : String.valueOf(msg.getContent());
        };
    }

    @SuppressWarnings("unchecked")
    private static ModelClientConfig buildClient(IntentDetectionConfig config, Map<String, Object> modelMap) {
        Map<String, Object> ext = config.extension();
        String apiKey = str(ext.get("api_key"));
        String apiBase = str(ext.get("api_base"));
        if (!apiKey.isBlank() && !apiBase.isBlank()) {
            return ModelClientConfig.builder()
                    .clientProvider(config.modelType())
                    .apiKey(apiKey)
                    .apiBase(apiBase)
                    .clientId(config.modelName())
                    .build();
        }
        if (modelMap != null && !modelMap.isEmpty()) {
            Object entry = modelMap.get(config.modelName());
            if (entry instanceof Map<?, ?> m) {
                Map<String, Object> dict = new LinkedHashMap<>();
                m.forEach((k, v) -> dict.put(String.valueOf(k), v));
                return ModelClientConfig.builder()
                        .clientProvider(str(dict.getOrDefault("client_provider", dict.get("clientProvider"))))
                        .apiKey(str(dict.get("api_key")))
                        .apiBase(str(dict.getOrDefault("api_base", dict.get("apiBase"))))
                        .clientId(str(dict.getOrDefault("client_id", config.modelName())))
                        .build();
            }
            throw new IllegalStateException(
                    "model_name '" + config.modelName() + "' not found in model_map and no extension config provided");
        }
        throw new IllegalStateException("Either extension config (api_key, api_base) or model_map is required");
    }

    private static ModelRequestConfig buildRequest(IntentDetectionConfig config) {
        Map<String, Object> hyper = config.hyperParameters();
        double temp = hyper.get("temperature") instanceof Number n ? n.doubleValue() : 0.1;
        double topP = hyper.get("top_p") instanceof Number n ? n.doubleValue() : 0.15;
        return ModelRequestConfig.builder()
                .modelName(config.modelName())
                .temperature(temp)
                .topP(topP)
                .build();
    }

    private static Object first(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            Object v = m.get(k);
            if (v != null && !String.valueOf(v).isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
