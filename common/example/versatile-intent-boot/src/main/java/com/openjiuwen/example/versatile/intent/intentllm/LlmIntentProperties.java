/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration for the LLM intent-classification handler.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.example.intent-llm")
public class LlmIntentProperties {
    private boolean enabled = false;
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private String model = "gpt-4o-mini";
    private Duration timeout = Duration.ofSeconds(30);
    private Double temperature = 0.0;
    private int maxRetries = 1;
    /** hotel | flight | null(全领域). 限定 L2 分类范围；L1 留空. */
    private String domain;

    public void validate() {
        require(apiKey, "openjiuwen.example.intent-llm.api-key");
        require(baseUrl, "openjiuwen.example.intent-llm.base-url");
        require(model, "openjiuwen.example.intent-llm.model");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when intent-llm.enabled=true");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
}