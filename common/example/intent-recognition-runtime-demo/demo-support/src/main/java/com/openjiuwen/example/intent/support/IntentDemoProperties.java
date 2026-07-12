/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intent.support;

import com.openjiuwen.core.foundation.llm.schema.BaseModelInfo;
import com.openjiuwen.core.foundation.llm.schema.ModelConfig;
import com.openjiuwen.core.retrieval.common.RerankerConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** External model and recognition settings shared by both demo runtimes. */
@ConfigurationProperties(prefix = "openjiuwen.demo.intent")
public class IntentDemoProperties {
    private final Llm llm = new Llm();
    private final RerankerModel reranker = new RerankerModel();
    private final Recognition recognition = new Recognition();

    public void requireConfigured() {
        llm.requireConfigured();
        reranker.requireConfigured();
    }

    public RerankerConfig toRerankerConfig() {
        reranker.requireConfigured();
        RerankerConfig config = new RerankerConfig();
        config.setApiKey(reranker.apiKey);
        config.setApiBase(reranker.apiBase);
        config.setModelName(reranker.modelName);
        config.setTimeout(reranker.timeoutSeconds);
        config.setExtraBody(reranker.extraBody);
        return config;
    }

    public ModelConfig toModelConfig() {
        llm.requireConfigured();
        BaseModelInfo modelInfo = BaseModelInfo.builder().apiKey(llm.apiKey).apiBase(llm.apiBase)
                .modelName(llm.modelName).temperature(llm.temperature).topP(llm.topP).timeout(llm.timeoutSeconds)
                .verifySsl(llm.sslVerify).build();
        return new ModelConfig(llm.provider, modelInfo);
    }

    public Llm getLlm() {
        return llm;
    }

    public RerankerModel getReranker() {
        return reranker;
    }

    public Recognition getRecognition() {
        return recognition;
    }

    /** Chat model settings used by ReActAgent and WorkflowAgent. */
    public static class Llm {
        private String provider = "OpenAI";
        private String apiKey = "";
        private String apiBase = "";
        private String modelName = "";
        private boolean sslVerify = true;
        private int timeoutSeconds = 120;
        private double temperature = 0.0;
        private double topP = 0.8;
        private int maxTokens = 1024;
        private int maxIterations = 4;
        private String systemPrompt = "You are an intent routing agent. For every user request, call the "
                + "intent_recognition tool exactly once with the original request as utterance. "
                + "Then report whether a target matched and include the selected target name and reason.";

        void requireConfigured() {
            requireText(provider, "openjiuwen.demo.intent.llm.provider");
            requireText(apiKey, "openjiuwen.demo.intent.llm.api-key");
            requireText(apiBase, "openjiuwen.demo.intent.llm.api-base");
            requireText(modelName, "openjiuwen.demo.intent.llm.model-name");
            requirePositive(timeoutSeconds, "openjiuwen.demo.intent.llm.timeout-seconds");
            requirePositive(maxTokens, "openjiuwen.demo.intent.llm.max-tokens");
            requirePositive(maxIterations, "openjiuwen.demo.intent.llm.max-iterations");
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public boolean isSslVerify() {
            return sslVerify;
        }

        public void setSslVerify(boolean sslVerify) {
            this.sslVerify = sslVerify;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public double getTopP() {
            return topP;
        }

        public void setTopP(double topP) {
            this.topP = topP;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }
    }

    /** StandardReranker endpoint settings. */
    public static class RerankerModel {
        private String apiKey = "";
        private String apiBase = "";
        private String modelName = "";
        private double timeoutSeconds = 30.0;
        private int maxRetries = 3;
        private Map<String, Object> extraBody = new LinkedHashMap<>();

        void requireConfigured() {
            requireText(apiKey, "openjiuwen.demo.intent.reranker.api-key");
            requireText(apiBase, "openjiuwen.demo.intent.reranker.api-base");
            requireText(modelName, "openjiuwen.demo.intent.reranker.model-name");
            requirePositive(timeoutSeconds, "openjiuwen.demo.intent.reranker.timeout-seconds");
            requirePositive(maxRetries, "openjiuwen.demo.intent.reranker.max-retries");
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiBase() {
            return apiBase;
        }

        public void setApiBase(String apiBase) {
            this.apiBase = apiBase;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }

        public double getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(double timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public Map<String, Object> getExtraBody() {
            return new LinkedHashMap<>(extraBody);
        }

        public void setExtraBody(Map<String, Object> extraBody) {
            this.extraBody = extraBody == null ? new LinkedHashMap<>() : new LinkedHashMap<>(extraBody);
        }
    }

    /** Calibrated recognition thresholds and capacity limits. */
    public static class Recognition {
        private double scoreThreshold = 0.5;
        private double marginThreshold = 0.1;
        private int maxUtteranceLength = 4096;
        private int maxBatchSize = 128;
        private int maxConcurrentRecognitions = 8;

        public double getScoreThreshold() {
            return scoreThreshold;
        }

        public void setScoreThreshold(double scoreThreshold) {
            this.scoreThreshold = scoreThreshold;
        }

        public double getMarginThreshold() {
            return marginThreshold;
        }

        public void setMarginThreshold(double marginThreshold) {
            this.marginThreshold = marginThreshold;
        }

        public int getMaxUtteranceLength() {
            return maxUtteranceLength;
        }

        public void setMaxUtteranceLength(int maxUtteranceLength) {
            this.maxUtteranceLength = maxUtteranceLength;
        }

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
        }

        public int getMaxConcurrentRecognitions() {
            return maxConcurrentRecognitions;
        }

        public void setMaxConcurrentRecognitions(int maxConcurrentRecognitions) {
            this.maxConcurrentRecognitions = maxConcurrentRecognitions;
        }
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
    }

    private static void requirePositive(double value, String propertyName) {
        if (!Double.isFinite(value) || value <= 0) {
            throw new IllegalStateException(propertyName + " must be greater than zero");
        }
    }
}
