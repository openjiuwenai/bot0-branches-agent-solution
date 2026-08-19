/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.openjiuwen.core.retrieval.common.RerankerConfig;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared external-model settings for all bank demo runtimes.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "bank.demo")
public class BankDemoProperties {
    private final Llm llm = new Llm();
    private final RerankerModel reranker = new RerankerModel();

    /**
     * Returns the chat model properties.
     *
     * @return chat model properties
     */
    public Llm getLlm() {
        return llm;
    }

    /**
     * Returns the reranker properties.
     *
     * @return reranker properties
     */
    public RerankerModel getReranker() {
        return reranker;
    }

    /**
     * Validates that required chat model properties are configured.
     */
    public void requireLlm() {
        llm.requireConfigured();
    }

    /**
     * Creates the DeepAgent model configuration.
     *
     * @return model configuration
     */
    public Map<String, Object> modelConfig() {
        requireLlm();
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", llm.modelName);
        model.put("temperature", llm.temperature);
        model.put("top_p", llm.topP);
        return model;
    }

    /**
     * Creates the DeepAgent backend configuration.
     *
     * @return backend configuration
     */
    public Map<String, Object> backendConfig() {
        requireLlm();
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("provider", llm.provider);
        backend.put("api_key", llm.apiKey);
        backend.put("api_base", llm.apiBase);
        backend.put("verify_ssl", llm.sslVerify);
        backend.put("timeout", llm.timeout.toSeconds());
        return backend;
    }

    /**
     * Creates the reranker configuration.
     *
     * @return reranker configuration
     */
    public RerankerConfig rerankerConfig() {
        reranker.requireConfigured();
        RerankerConfig config = new RerankerConfig();
        config.setApiKey(reranker.apiKey);
        config.setApiBase(reranker.apiBase);
        config.setModelName(reranker.modelName);
        config.setTimeout(reranker.timeoutSeconds);
        config.setExtraBody(reranker.extraBody);
        return config;
    }

    /**
     * Chat model settings.
     *
     * @since 0.1.0
     */
    public static class Llm {
        private String provider = "OpenAI";
        private String apiKey = "";
        private String apiBase = "";
        private String modelName = "";
        private boolean sslVerify = true;
        private double temperature;
        private double topP = 0.8D;
        private Duration timeout = Duration.ofSeconds(120);
        private Duration completionTimeout = Duration.ofSeconds(600);
        private int maxIterations = 12;

        void requireConfigured() {
            requireText(provider, "bank.demo.llm.provider");
            requireText(apiKey, "bank.demo.llm.api-key");
            requireText(apiBase, "bank.demo.llm.api-base");
            requireText(modelName, "bank.demo.llm.model-name");
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

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public Duration getCompletionTimeout() {
            return completionTimeout;
        }

        public void setCompletionTimeout(Duration completionTimeout) {
            this.completionTimeout = completionTimeout;
        }

        public int getMaxIterations() {
            return maxIterations;
        }

        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }
    }

    /**
     * Reranker endpoint settings used only by the Intent Agent.
     *
     * @since 0.1.0
     */
    public static class RerankerModel {
        private String apiKey = "";
        private String apiBase = "";
        private String modelName = "";
        private double timeoutSeconds = 30.0D;
        private int maxRetries = 3;
        private Map<String, Object> extraBody = new LinkedHashMap<>();

        void requireConfigured() {
            requireText(apiKey, "bank.demo.reranker.api-key");
            requireText(apiBase, "bank.demo.reranker.api-base");
            requireText(modelName, "bank.demo.reranker.model-name");
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

    private static void requireText(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " is required");
        }
    }
}
