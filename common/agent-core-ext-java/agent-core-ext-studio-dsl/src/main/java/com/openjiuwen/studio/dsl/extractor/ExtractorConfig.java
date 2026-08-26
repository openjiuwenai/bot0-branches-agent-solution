/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.extractor;

import java.util.List;
import java.util.Map;

/**
 * Parsed Extractor IR config (Python {@code ExtractorConfig} + {@code format_extractor_configs}).
 *
 * @since 2026-08-26
 */
public final class ExtractorConfig {
    private final String modelName;
    private final String modelType;
    private final boolean withChatHistory;
    private final int chatHistoryMaxRounds;
    private final String extraPromptForFieldsExtraction;
    private final String questionContent;
    private final Map<String, String> cnFieldsName;
    private final List<Map<String, Object>> keyFields;
    private final Map<String, Object> hyperParameters;
    private final Map<String, Object> extension;
    private final List<Map<String, String>> promptTemplate;
    private final String exampleContent;
    private final Boolean inputComplement;
    private final Boolean extractFieldsFromResponse;
    private final Map<String, Object> rawConfigs;

    ExtractorConfig(
            String modelName,
            String modelType,
            boolean withChatHistory,
            int chatHistoryMaxRounds,
            String extraPromptForFieldsExtraction,
            String questionContent,
            Map<String, String> cnFieldsName,
            List<Map<String, Object>> keyFields,
            Map<String, Object> hyperParameters,
            Map<String, Object> extension,
            List<Map<String, String>> promptTemplate,
            String exampleContent,
            Boolean inputComplement,
            Boolean extractFieldsFromResponse,
            Map<String, Object> rawConfigs) {
        this.modelName = modelName;
        this.modelType = modelType;
        this.withChatHistory = withChatHistory;
        this.chatHistoryMaxRounds = chatHistoryMaxRounds;
        this.extraPromptForFieldsExtraction = extraPromptForFieldsExtraction == null ? "" : extraPromptForFieldsExtraction;
        this.questionContent = questionContent == null ? "" : questionContent;
        this.cnFieldsName = cnFieldsName == null ? Map.of() : Map.copyOf(cnFieldsName);
        this.keyFields = keyFields == null ? List.of() : List.copyOf(keyFields);
        this.hyperParameters = hyperParameters == null ? Map.of() : Map.copyOf(hyperParameters);
        this.extension = extension == null ? Map.of() : Map.copyOf(extension);
        this.promptTemplate = promptTemplate == null ? List.of() : List.copyOf(promptTemplate);
        this.exampleContent = exampleContent == null ? ExtractorLlmExtractor.DEFAULT_EXAMPLE_CONTENT : exampleContent;
        this.inputComplement = inputComplement;
        this.extractFieldsFromResponse = extractFieldsFromResponse;
        this.rawConfigs = rawConfigs == null ? Map.of() : Map.copyOf(rawConfigs);
    }

    public static ExtractorConfig fromNodeConfigs(Map<String, Object> configs) {
        return ExtractorConfigFormatter.format(configs == null ? Map.of() : configs);
    }

    public String modelName() {
        return modelName;
    }

    public String modelType() {
        return modelType;
    }

    public boolean withChatHistory() {
        return withChatHistory;
    }

    public int chatHistoryMaxRounds() {
        return chatHistoryMaxRounds;
    }

    public String extraPromptForFieldsExtraction() {
        return extraPromptForFieldsExtraction;
    }

    public String questionContent() {
        return questionContent;
    }

    public Map<String, String> cnFieldsName() {
        return cnFieldsName;
    }

    public List<Map<String, Object>> keyFields() {
        return keyFields;
    }

    public Map<String, Object> hyperParameters() {
        return hyperParameters;
    }

    public Map<String, Object> extension() {
        return extension;
    }

    public List<Map<String, String>> promptTemplate() {
        return promptTemplate;
    }

    public String exampleContent() {
        return exampleContent;
    }

    /** Python {@code input_complement} — validated at init; no runtime rail in extension extractor. */
    public Boolean inputComplement() {
        return inputComplement;
    }

    /** Python {@code extract_fields_from_response} — validated at init; unused at runtime in extension extractor. */
    public Boolean extractFieldsFromResponse() {
        return extractFieldsFromResponse;
    }

    public Map<String, Object> rawConfigs() {
        return rawConfigs;
    }
}
