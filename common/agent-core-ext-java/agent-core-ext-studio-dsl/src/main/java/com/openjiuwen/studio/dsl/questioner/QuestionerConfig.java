/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.questioner;

import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Questioner IR config (Python {@code QuestionerConfig}).
 *
 * @since 2026-08-25
 */
public final class QuestionerConfig {
    private final String questionContent;
    private final boolean extractFieldsFromResponse;
    private final String questionConstructionMethod;
    private final int maxResponse;
    private final String acceptLanguage;
    private final String autoAskTemplate;
    private final boolean allowNodeConfirm;
    private final boolean allowNodeBreak;
    private final boolean enumVisible;
    private final List<QuestionerField> keyFields;
    private final Map<String, Object> railsConfig;
    private final Map<String, Object> mockExtractedFields;
    private final String extraPromptForFieldsExtraction;
    private final String exampleContent;
    private final String promptTemplate;
    private final boolean withChatHistory;
    private final ModelClientConfig modelClientConfig;
    private final ModelRequestConfig modelRequestConfig;
    private final Map<String, Object> rawConfigs;

    QuestionerConfig(
            String questionContent,
            boolean extractFieldsFromResponse,
            String questionConstructionMethod,
            int maxResponse,
            String acceptLanguage,
            String autoAskTemplate,
            boolean allowNodeConfirm,
            boolean allowNodeBreak,
            boolean enumVisible,
            List<QuestionerField> keyFields,
            Map<String, Object> railsConfig,
            Map<String, Object> mockExtractedFields,
            String extraPromptForFieldsExtraction,
            String exampleContent,
            String promptTemplate,
            boolean withChatHistory,
            ModelClientConfig modelClientConfig,
            ModelRequestConfig modelRequestConfig,
            Map<String, Object> rawConfigs) {
        this.questionContent = questionContent;
        this.extractFieldsFromResponse = extractFieldsFromResponse;
        this.questionConstructionMethod = questionConstructionMethod;
        this.maxResponse = maxResponse;
        this.acceptLanguage = acceptLanguage;
        this.autoAskTemplate = autoAskTemplate;
        this.allowNodeConfirm = allowNodeConfirm;
        this.allowNodeBreak = allowNodeBreak;
        this.enumVisible = enumVisible;
        this.keyFields = List.copyOf(keyFields);
        this.railsConfig = railsConfig;
        this.mockExtractedFields = mockExtractedFields;
        this.extraPromptForFieldsExtraction = extraPromptForFieldsExtraction == null ? "" : extraPromptForFieldsExtraction;
        this.exampleContent = exampleContent == null ? "" : exampleContent;
        this.promptTemplate = promptTemplate == null ? "" : promptTemplate;
        this.withChatHistory = withChatHistory;
        this.modelClientConfig = modelClientConfig;
        this.modelRequestConfig = modelRequestConfig;
        this.rawConfigs = rawConfigs == null ? Map.of() : Map.copyOf(rawConfigs);
    }

    @SuppressWarnings("unchecked")
    public static QuestionerConfig fromNodeConfigs(Map<String, Object> configs) {
        Map<String, Object> c = configs == null ? Map.of() : configs;
        String questionContent = str(c.getOrDefault("questionContent", c.getOrDefault("question", c.get("prompt"))));
        boolean extract = bool(c.get("extractFieldsFromResponse"), true);
        String method = str(c.getOrDefault("questionConstructionMethod", "rule_based"));
        if (method.isBlank()) {
            method = "rule_based";
        }
        int maxResponse = 3;
        Object mr = c.get("maxResponse");
        if (mr instanceof Number n) {
            maxResponse = n.intValue();
        }
        String lang = str(c.getOrDefault("acceptLanguage", "zh"));
        String template = str(c.get("autoAskTemplate"));
        boolean allowConfirm = bool(c.get("allowNodeConfirm"), false);
        boolean allowBreak = bool(c.get("allowNodeBreak"), false);
        boolean enumVisible = bool(c.get("enumVisible"), false);
        List<QuestionerField> fields = parseFields(c);
        Map<String, Object> rails = mapOf(c.get("railsConfig"));
        Map<String, Object> mock = mapOf(c.get("mockExtractedFields"));
        String extra = str(c.getOrDefault("extraPromptForFieldsExtraction", c.get("extra_prompt_for_fields_extraction")));
        String example = str(c.getOrDefault("exampleContent", c.get("example_content")));
        String promptTpl = str(c.getOrDefault("promptTemplate", c.get("prompt_template")));
        boolean withChat = bool(c.getOrDefault("withChatHistory", c.get("with_chat_history")), false);

        ModelClientConfig clientCfg = null;
        ModelRequestConfig reqCfg = null;
        if (QuestionerLlmExtractor.hasModelWiring(c)) {
            clientCfg = buildClient(c);
            reqCfg = buildRequest(c);
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        c.forEach((k, v) -> raw.put(String.valueOf(k), v));

        return new QuestionerConfig(
                questionContent,
                extract,
                method,
                maxResponse,
                lang,
                template,
                allowConfirm,
                allowBreak,
                enumVisible,
                fields,
                rails,
                mock,
                extra,
                example,
                promptTpl,
                withChat,
                clientCfg,
                reqCfg,
                raw);
    }

    private static ModelClientConfig buildClient(Map<String, Object> c) {
        Object nested = c.get("modelClientConfig");
        ModelClientConfig.Builder b = ModelClientConfig.builder();
        Map<String, Object> src = nested instanceof Map<?, ?> m ? cast(m) : c;
        if (src.get("apiKey") != null) {
            b.apiKey(String.valueOf(src.get("apiKey")));
        }
        Object base = src.getOrDefault("apiBase", src.get("baseUrl"));
        if (base != null) {
            b.apiBase(String.valueOf(base));
        }
        Object provider = src.getOrDefault("clientProvider", src.get("provider"));
        b.clientProvider(provider == null ? "OpenAI" : String.valueOf(provider));
        Object clientId = src.get("clientId");
        if (clientId != null) {
            b.clientId(String.valueOf(clientId));
        } else {
            Object model = c.getOrDefault("modelId", c.getOrDefault("model", c.get("modelName")));
            b.clientId(model == null ? "studio-questioner" : String.valueOf(model));
        }
        return b.build();
    }

    private static ModelRequestConfig buildRequest(Map<String, Object> c) {
        Object nested = c.get("modelConfig");
        Map<String, Object> src = nested instanceof Map<?, ?> m ? cast(m) : c;
        ModelRequestConfig.ModelRequestConfigBuilder b = ModelRequestConfig.builder();
        Object name = src.getOrDefault("modelName", src.getOrDefault("model", c.get("model")));
        if (name != null) {
            b.modelName(String.valueOf(name));
        }
        return b.build();
    }

    private static Map<String, Object> cast(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<QuestionerField> parseFields(Map<String, Object> c) {
        Object raw = c.get("fieldNames");
        if (raw == null) {
            raw = c.get("keyFields");
        }
        if (raw == null) {
            Object uf = c.get("userFields");
            if (uf instanceof Map<?, ?> m) {
                raw = m.get("inputs");
            }
        }
        List<QuestionerField> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    Map<String, Object> cast = new LinkedHashMap<>();
                    m.forEach((k, v) -> cast.put(String.valueOf(k), v));
                    out.add(QuestionerField.fromMap(cast));
                } else if (item instanceof String s) {
                    out.add(new QuestionerField(s, "", "string", s, true, null, false));
                }
            }
        }
        return out;
    }

    private static Map<String, Object> mapOf(Object o) {
        if (!(o instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o == null) {
            return def;
        }
        return Boolean.parseBoolean(String.valueOf(o));
    }

    public String questionContent() {
        return questionContent;
    }

    public boolean extractFieldsFromResponse() {
        return extractFieldsFromResponse;
    }

    public String questionConstructionMethod() {
        return questionConstructionMethod;
    }

    public int maxResponse() {
        return maxResponse;
    }

    public String acceptLanguage() {
        return acceptLanguage;
    }

    public String autoAskTemplate() {
        return autoAskTemplate;
    }

    public boolean allowNodeConfirm() {
        return allowNodeConfirm;
    }

    public boolean allowNodeBreak() {
        return allowNodeBreak;
    }

    public boolean enumVisible() {
        return enumVisible;
    }

    public List<QuestionerField> keyFields() {
        return keyFields;
    }

    public Map<String, Object> railsConfig() {
        return railsConfig;
    }

    public Map<String, Object> mockExtractedFields() {
        return mockExtractedFields;
    }

    public String extraPromptForFieldsExtraction() {
        return extraPromptForFieldsExtraction;
    }

    public String exampleContent() {
        return exampleContent;
    }

    public String promptTemplate() {
        return promptTemplate;
    }

    public boolean withChatHistory() {
        return withChatHistory;
    }

    public ModelClientConfig modelClientConfig() {
        return modelClientConfig;
    }

    public ModelRequestConfig modelRequestConfig() {
        return modelRequestConfig;
    }

    public Map<String, Object> rawConfigs() {
        return rawConfigs;
    }

    public boolean hasModelWiring() {
        return modelClientConfig != null && modelRequestConfig != null;
    }

    public boolean hasQuestionContent() {
        return questionContent != null && !questionContent.isBlank();
    }

    public boolean needExtractFields() {
        return extractFieldsFromResponse && !keyFields.isEmpty();
    }

    /**
     * Python {@code _need_extract_fields} — still extracting when configured fields exceed extracted count.
     *
     * @param state state
     * @return true when more extraction rounds are needed
     */
    public boolean needExtractFields(QuestionerState state) {
        if (!extractFieldsFromResponse || keyFields.isEmpty()) {
            return false;
        }
        int extracted = state == null ? 0 : state.extractedFields().size();
        return keyFields.size() > extracted;
    }
}
