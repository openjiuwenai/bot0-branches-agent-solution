/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.intentdetection;

import java.util.List;
import java.util.Map;

/**
 * Parsed IntentDetection IR config (Python {@code IntentDetectionConfig} + {@code _get_config_info}).
 *
 * @since 2026-08-26
 */
public final class IntentDetectionConfig {
    static final String DEFAULT_CLASS_ID = "分类0";
    static final String DEFAULT_CLASS_ID_LEGACY = "分类1";
    static final String DEFAULT_CLASS_NAME = "其他意图";

    private final String userPrompt;
    private final String categoryInfo;
    private final List<String> categoryList;
    private final List<String> categoryNameList;
    private final String defaultClass;
    private final boolean enableHistory;
    private final boolean enableInput;
    private final int chatHistoryMaxTurn;
    private final List<String> exampleContent;
    private final boolean overridable;
    private final boolean enableKnowledge;
    private final double recallThreshold;
    private final double q2labelFewShotScore;
    private final Map<String, Object> kgConfig;
    private final String kgScope;
    private final String modelName;
    private final String modelType;
    private final Map<String, Object> hyperParameters;
    private final Map<String, Object> extension;
    private final List<Map<String, Object>> branches;
    private final Map<String, Object> rawConfigs;

    IntentDetectionConfig(
            String userPrompt,
            String categoryInfo,
            List<String> categoryList,
            List<String> categoryNameList,
            String defaultClass,
            boolean enableHistory,
            boolean enableInput,
            int chatHistoryMaxTurn,
            List<String> exampleContent,
            boolean overridable,
            boolean enableKnowledge,
            double recallThreshold,
            double q2labelFewShotScore,
            Map<String, Object> kgConfig,
            String kgScope,
            String modelName,
            String modelType,
            Map<String, Object> hyperParameters,
            Map<String, Object> extension,
            List<Map<String, Object>> branches,
            Map<String, Object> rawConfigs) {
        this.userPrompt = userPrompt == null ? "" : userPrompt;
        this.categoryInfo = categoryInfo == null ? "" : categoryInfo;
        this.categoryList = categoryList == null ? List.of() : List.copyOf(categoryList);
        this.categoryNameList = categoryNameList == null ? List.of() : List.copyOf(categoryNameList);
        this.defaultClass = defaultClass == null ? DEFAULT_CLASS_ID : defaultClass;
        this.enableHistory = enableHistory;
        this.enableInput = enableInput;
        this.chatHistoryMaxTurn = chatHistoryMaxTurn;
        this.exampleContent = exampleContent == null ? List.of() : List.copyOf(exampleContent);
        this.overridable = overridable;
        this.enableKnowledge = enableKnowledge;
        this.recallThreshold = recallThreshold;
        this.q2labelFewShotScore = q2labelFewShotScore;
        this.kgConfig = kgConfig == null ? Map.of() : Map.copyOf(kgConfig);
        this.kgScope = kgScope == null || kgScope.isBlank() ? "faq" : kgScope;
        this.modelName = modelName == null ? "" : modelName;
        this.modelType = modelType == null ? "" : modelType;
        this.hyperParameters = hyperParameters == null ? Map.of() : Map.copyOf(hyperParameters);
        this.extension = extension == null ? Map.of() : Map.copyOf(extension);
        this.branches = branches == null ? List.of() : List.copyOf(branches);
        this.rawConfigs = rawConfigs == null ? Map.of() : Map.copyOf(rawConfigs);
    }

    public static IntentDetectionConfig fromNodeConfigs(Map<String, Object> configs) {
        return IntentDetectionConfigFormatter.format(configs == null ? Map.of() : configs);
    }

    public String userPrompt() {
        return userPrompt;
    }

    public String categoryInfo() {
        return categoryInfo;
    }

    public List<String> categoryList() {
        return categoryList;
    }

    public List<String> categoryNameList() {
        return categoryNameList;
    }

    public String defaultClass() {
        return defaultClass;
    }

    public boolean enableHistory() {
        return enableHistory;
    }

    public boolean enableInput() {
        return enableInput;
    }

    public int chatHistoryMaxTurn() {
        return chatHistoryMaxTurn;
    }

    public List<String> exampleContent() {
        return exampleContent;
    }

    public boolean overridable() {
        return overridable;
    }

    public boolean enableKnowledge() {
        return enableKnowledge;
    }

    public double recallThreshold() {
        return recallThreshold;
    }

    public double q2labelFewShotScore() {
        return q2labelFewShotScore;
    }

    public Map<String, Object> kgConfig() {
        return kgConfig;
    }

    public String kgScope() {
        return kgScope;
    }

    public String modelName() {
        return modelName;
    }

    public String modelType() {
        return modelType;
    }

    public Map<String, Object> hyperParameters() {
        return hyperParameters;
    }

    public Map<String, Object> extension() {
        return extension;
    }

    public List<Map<String, Object>> branches() {
        return branches;
    }

    public Map<String, Object> rawConfigs() {
        return rawConfigs;
    }

    public boolean hasModelWiring() {
        if (!modelName.isBlank() && !modelType.isBlank()) {
            return true;
        }
        return IntentDetectionLlmDetector.hasModelWiring(rawConfigs);
    }
}
