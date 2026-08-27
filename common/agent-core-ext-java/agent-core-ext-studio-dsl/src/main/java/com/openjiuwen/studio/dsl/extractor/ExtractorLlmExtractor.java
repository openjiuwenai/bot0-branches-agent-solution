/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.extractor;

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
import java.util.stream.Collectors;

/**
 * LLM field extraction for Extractor (Python {@code extract_key_fields} / {@code create_prompt_template_input}).
 *
 * @since 2026-08-26
 */

public final class ExtractorLlmExtractor {
    static final String DEFAULT_EXAMPLE_CONTENT =
            """
            用户输入: 我是小明，性别是男
            指定参数：[name:姓名, age:年龄, gender:性别]
            提取结果：{"name":"小明","age":null,"gender":"男"}
            """;

    private static final String DEFAULT_SYSTEM_EXTRA_PROMPT =
            """
            你是一个信息收集助手，你需要根据指定的参数从用户输入中提取信息。
            请注意：不要使用任何工具、不用理会问题的具体含义，并保证你的输出仅有 JSON 格式的结果数据。
            请严格遵循如下规则：
              1. 让我们一步一步思考。
              2. 用户输入中没有提及的参数提取为 null。
              3. 通过用户提供的对话历史以及当前输入中提取 {{required_name}}，不要追问任何其他信息。
              4. 参数收集完成后，将收集到的信息通过 JSON 的方式展示给用户。

            ## 指定参数
            {{required_params_list}}

            ## 约束
            {{extra_info}}

            ## 示例
            {{example}}
            """;

    private static final String DEFAULT_USER_EXTRA_PROMPT =
            """
            对话历史
            {{dig_history}}

            请充分考虑以上对话历史及用户输入，正确提取最符合约束要求的 JSON 格式参数。
            """;

    /**
     * Injectable for tests (returns raw LLM text).
     */
    @FunctionalInterface
    public interface ModelInvoker {
        String invoke(List<BaseMessage> messages) throws Exception;
    }

    private final String nodeId;
    private final ExtractorConfig config;
    private final ModelInvoker invoker;

    public ExtractorLlmExtractor(String nodeId, ExtractorConfig config) {
        this(nodeId, config, null, Map.of());
    }
    public ExtractorLlmExtractor(String nodeId, ExtractorConfig config, ModelInvoker invoker) {
        this(nodeId, config, invoker, Map.of());
    }
    public ExtractorLlmExtractor(
            String nodeId, ExtractorConfig config, ModelInvoker invoker, Map<String, Object> modelMap) {
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
        Object model = configs.get("model");
        if (model instanceof Map<?, ?> m) {
            Object name = first(m, "modelName", "model_name", "name");
            Object type = first(m, "modelType", "model_type", "type");
            return name != null
                    && !String.valueOf(name).isBlank()
                    && type != null
                    && !String.valueOf(type).isBlank();
        }
        return QuestionerLlmExtractor.hasModelWiring(configs);
    }

    public Map<String, Object> extract(
            String userResponse, List<Map<String, Object>> chatHistory, NodeSessionApi session) {
        Map<String, String> templateInput = createPromptTemplateInput(userResponse, chatHistory);
        List<BaseMessage> messages = formatPrompt(templateInput);
        trace(session, Map.of("llm_inputs", summarizeMessages(messages)));
        String response;
        try {
            response = invoker.invoke(messages);
        } catch (Exception e) {
            throw new NodeExecutionException(
                    nodeId,
                    "jiuwen.extractor",
                    NodeCauseCode.NODE_INVOKE_FAILED,
                    "extractor invoke llm failed: " + e.getMessage(),
                    e);
        }
        trace(session, Map.of("llm_outputs", response == null ? "" : response));
        return parseAndNormalize(response);
    }

    private List<BaseMessage> formatPrompt(Map<String, String> templateInput) {
        List<BaseMessage> out = new ArrayList<>();
        if (!config.promptTemplate().isEmpty()) {
            for (Map<String, String> turn : config.promptTemplate()) {
                String role = turn.getOrDefault("role", "");
                String content = replacePlaceholders(turn.getOrDefault("content", ""), templateInput);
                if ("system".equalsIgnoreCase(role)) {
                    out.add(new SystemMessage(content));
                } else if ("user".equalsIgnoreCase(role)) {
                    out.add(new UserMessage(content));
                }
            }
            return out;
        }
        out.add(new SystemMessage(replacePlaceholders(DEFAULT_SYSTEM_EXTRA_PROMPT, templateInput)));
        out.add(new UserMessage(replacePlaceholders(DEFAULT_USER_EXTRA_PROMPT, templateInput)));
        return out;
    }

    private Map<String, String> createPromptTemplateInput(
            String userResponse, List<Map<String, Object>> chatHistory) {
        List<String> requiredParams = new ArrayList<>();
        for (int i = 0; i < config.keyFields().size(); i++) {
            Map<String, Object> param = config.keyFields().get(i);
            int number = i + 1;
            requiredParams.add(
                    "变量"
                            + number
                            + "名称："
                            + str(param.get("name"))
                            + "，变量"
                            + number
                            + "的描述："
                            + str(param.get("desc"))
                            + " ");
        }

        List<String> requiredName = new ArrayList<>(config.cnFieldsName().values());
        String requiredNameStr =
                String.join("、", requiredName) + config.cnFieldsName().size() + "个必要信息";

        String resolvedUser = userResponse == null || userResponse.isBlank()
                ? lastContent(chatHistory)
                : userResponse;

        List<String> requiredParamsList = new ArrayList<>();
        for (Map<String, Object> param : config.keyFields()) {
            requiredParamsList.add(str(param.get("name")) + ":" + str(param.get("desc")) + " ");
        }

        List<Map<String, Object>> filtered = filterEnableHistory(chatHistory);
        List<Map<String, Object>> digSource = new ArrayList<>(filtered.subList(0, Math.max(0, filtered.size() - 1)));
        digSource.add(Map.of("role", "user", "content", resolvedUser == null ? "" : resolvedUser));
        String digHistory = digSource.stream()
                .map(u -> str(u.get("role")) + "：" + str(u.get("content")))
                .collect(Collectors.joining("\n"));

        String extraInfo = config.extraPromptForFieldsExtraction();
        String requiredParamsJoined = " \n " + String.join(" \n ", requiredParams);

        Map<String, String> out = new LinkedHashMap<>();
        out.put("required_params", requiredParamsJoined);
        out.put("example", config.exampleContent());
        out.put("dig_history", digHistory);
        out.put("required_name", requiredNameStr);
        out.put("extra_info", extraInfo);
        out.put("required_params_list", String.join("\n", requiredParamsList));
        return out;
    }

    private Map<String, Object> parseAndNormalize(String response) {
        Map<String, Object> parsed = QuestionerLlmExtractor.parseJsonObject(response);
        Map<String, Object> out = new LinkedHashMap<>();
        parsed.forEach((k, v) -> {
            if (v == null || (v instanceof String s && s.isBlank())) {
                out.put(k, "");
            } else {
                out.put(k, v);
            }
        });
        return out;
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

    static List<Map<String, Object>> getLatestKRoundsChat(List<Map<String, Object>> chatHistory, Integer k) {
        if (chatHistory == null || chatHistory.isEmpty()) {
            return List.of();
        }
        if (k == null) {
            return new ArrayList<>(chatHistory);
        }
        int from = Math.max(0, chatHistory.size() - (k * 2 + 1));
        return new ArrayList<>(chatHistory.subList(from, chatHistory.size()));
    }

    private static String lastContent(List<Map<String, Object>> chatHistory) {
        if (chatHistory == null || chatHistory.isEmpty()) {
        return "";
    }
        return str(chatHistory.get(chatHistory.size() - 1).get("content"));
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
        } catch (RuntimeException ignored) {
            // mock
        }
    }

    private static ModelInvoker defaultInvoker(ExtractorConfig config, Map<String, Object> modelMap) {
        return createDefaultInvoker(config, modelMap);
    }

    /**
     * Validates wiring and returns production invoker (Python {@code _create_llm_instance}).
     *
     * @param config config
     * @param modelMap modelMap
     * @return result
     * @since 0.1.0
     */
    public static ModelInvoker createDefaultInvoker(ExtractorConfig config, Map<String, Object> modelMap) {
        return messages -> {
            ModelClientConfig client = buildClient(config, modelMap);
            ModelRequestConfig request = buildRequest(config);
            Model model = new Model(client, request);
            AssistantMessage msg = model.invoke(messages, null, null, null, null, null, null, null, null, null);
            return msg.getContent() == null ? "" : String.valueOf(msg.getContent());
        };
    }

    @SuppressWarnings("unchecked")
    private static ModelClientConfig buildClient(ExtractorConfig config, Map<String, Object> modelMap) {
        Map<String, Object> ext = config.extension();
        String apiKey = str(ext.get("api_key"));
        String apiBase = str(ext.get("api_base"));
        boolean verifySsl = !Boolean.FALSE.equals(ext.get("verify_ssl"));
        if (!apiKey.isBlank() && !apiBase.isBlank()) {
            return ModelClientConfig.builder()
                    .clientProvider(config.modelType())
                    .apiKey(apiKey)
                    .apiBase(apiBase)
                    .verifySsl(verifySsl)
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
            throw new NodeExecutionException(
                    "n/a",
                    "jiuwen.extractor",
                    NodeCauseCode.NODE_CONFIG_INVALID,
                    "model_name '" + config.modelName() + "' not found in model_map and no extension config provided");
        }
        throw new NodeExecutionException(
                "n/a",
                "jiuwen.extractor",
                NodeCauseCode.NODE_CONFIG_INVALID,
                "Either extension config (api_key, api_base) or model_map is required");
    }

    private static ModelRequestConfig buildRequest(ExtractorConfig config) {
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
