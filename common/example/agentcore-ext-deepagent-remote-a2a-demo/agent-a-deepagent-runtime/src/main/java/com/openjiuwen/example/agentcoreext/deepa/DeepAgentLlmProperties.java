/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.deepa;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM backend configuration for Agent A.
 *
 * @since 2026-07-03
 */
@ConfigurationProperties(prefix = "openjiuwen.demo.deep-agent.llm")
public class DeepAgentLlmProperties {
    private String provider = "OpenAI";
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private String modelName = "deepseek-chat";
    private boolean isSslVerify = true;
    private String systemPrompt = """
            你是 Agent A，是三轮远程 A2A 工作流的入口和最终总结者。
            首轮用户请求到达且还没有远端 pending task 时，必须调用名为 agent-b 的远程 A2A tool。
            调用 agent-b 时只传入 JSON 参数 remoteInput，值必须是用户本轮原始消息文本，不要改写。
            第二轮和第三轮用户输入由 runtime shadow task/resume 机制直接续传给 Agent B，不需要你主动处理。
            如果本轮输入是 runtime 注入的 Agent B 最终结果，不要再次调用 agent-b，直接用简短中文总结。
            """;
    private Double temperature = 0.0;
    private Double topP = 0.8;
    private Duration timeout = Duration.ofSeconds(120);
    private Duration completionTimeout = Duration.ofSeconds(600);
    private int maxIterations = 8;
    private String workspacePath = "target/agent-a-workspace";

    void requireConfigured() {
        requireText(apiKey, "openjiuwen.demo.deep-agent.llm.api-key");
        requireText(apiBase, "openjiuwen.demo.deep-agent.llm.api-base");
        requireText(modelName, "openjiuwen.demo.deep-agent.llm.model-name");
    }

    Map<String, Object> modelConfig() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", modelName);
        model.put("temperature", temperature);
        model.put("top_p", topP);
        return model;
    }

    Map<String, Object> backendConfig() {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("provider", provider);
        backend.put("api_key", apiKey);
        backend.put("api_base", apiBase);
        backend.put("verify_ssl", isSslVerify);
        backend.put("timeout", timeout.toSeconds());
        return backend;
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
        return isSslVerify;
    }

    public void setSslVerify(boolean isSslVerify) {
        this.isSslVerify = isSslVerify;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
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

    public String getWorkspacePath() {
        return workspacePath;
    }

    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
    }
}
