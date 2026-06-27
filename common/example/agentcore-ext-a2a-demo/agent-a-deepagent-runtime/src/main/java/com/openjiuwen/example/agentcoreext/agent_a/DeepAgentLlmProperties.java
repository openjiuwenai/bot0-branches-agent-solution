package com.openjiuwen.example.agentcoreext.agent_a;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "openjiuwen.demo.deep-agent.llm")
public class DeepAgentLlmProperties {

    private String provider = "OpenAI";
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private String modelName = "deepseek-chat";
    private boolean sslVerify = true;
    private String systemPrompt = "You are Agent A. When the user asks for remote business processing, call the remote A2A tool.";
    private Double temperature = 0.2;
    private Double topP = 0.8;
    private Duration timeout = Duration.ofSeconds(120);
    private Duration completionTimeout = Duration.ofSeconds(600);
    private int maxIterations = 8;
    private String workspacePath = "target/deep-agent-workspace";
    private List<String> skillDirectories = List.of("common/example/agentcore-ext-a2a-demo/skills");
    private String skillMode = "all";

    public void requireConfigured() {
        requireText(apiKey, "openjiuwen.demo.deep-agent.llm.api-key");
        requireText(apiBase, "openjiuwen.demo.deep-agent.llm.api-base");
        requireText(modelName, "openjiuwen.demo.deep-agent.llm.model-name");
    }

    public Map<String, Object> modelConfig() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", modelName);
        model.put("temperature", temperature);
        model.put("top_p", topP);
        return model;
    }

    public Map<String, Object> backendConfig() {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("provider", provider);
        backend.put("api_key", apiKey);
        backend.put("api_base", apiBase);
        backend.put("verify_ssl", sslVerify);
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
        return sslVerify;
    }

    public void setSslVerify(boolean sslVerify) {
        this.sslVerify = sslVerify;
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

    public List<String> getSkillDirectories() {
        return skillDirectories;
    }

    public void setSkillDirectories(List<String> skillDirectories) {
        this.skillDirectories = skillDirectories;
    }

    public String getSkillMode() {
        return skillMode;
    }

    public void setSkillMode(String skillMode) {
        this.skillMode = skillMode;
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
    }
}
