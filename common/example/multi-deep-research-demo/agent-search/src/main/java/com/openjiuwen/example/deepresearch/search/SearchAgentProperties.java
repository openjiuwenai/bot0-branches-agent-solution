/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pure POJO holding configuration for the search ReAct sub-agent.
 *
 * <p>Library tier: depends only on JDK + agent-core-java, no Spring annotations.
 * The runtime wrapper subclasses this to attach {@code @ConfigurationProperties}.
 *
 * @since 2026-07-06
 */
public class SearchAgentProperties {
    private String agentId = "search-agent";
    private String agentName = "SearchAgent";
    private String agentDescription = "ReAct web-search sub-agent (Tavily + source-kind reranker)";

    private String provider = "OpenAI";
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private String modelName = "deepseek-chat";
    private boolean isSslVerify = true;
    private Double temperature = 0.2;
    private Double topP = 0.8;

    private int maxIterations = 4;
    private String sysOperationId = "deep-research-search-agent";

    /**
     * When {@code true}, the wrapper should wire {@link StubWebSearchTool#search}
     * as the tool body (fixture-backed). Default {@code false} = prod Tavily.
     */
    private boolean isUseStub = false;

    private String systemPrompt = """
            You are the search sub-agent of a deep-research multi-agent system.

            Your only tool is `web_search`, which you must call exactly once per user
            request unless the first call returns an empty `results` array — in that
            case, retry at most once with a reformulated query (drop vendor brand
            names, switch language) before giving up.

            Call signature:
              web_search({ query: string, top_k: int, time_range: "year"|"month"|"week"|"all", language: "zh"|"en"|"any" })

            Output contract (must match exactly so the root agent can parse it):
              {
                "results": [
                  { "url": "...", "title": "...", "snippet": "...",
                    "source_kind": "official|blog|news|forum", "score": 0.0 }
                ]
              }

            Rules:
            - Pass the user's query through verbatim — do not rewrite unless retrying.
            - Do not invent fields. The tool already classifies source_kind by host
              and reweights official sources; pass its output through unmodified.
            - Do not synthesise summaries — verification and synthesis happen in the
              root agent. Return ONLY the JSON object above.
            """;

    /**
     * Builds the JSON schema describing the {@code web_search} tool input contract
     * for the LLM (matches the {@link WebSearchTool} runtime signature).
     *
     * @return an ordered map keyed as {@code type} / {@code properties} / {@code required}
     */
    public Map<String, Object> webSearchInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("query", Map.of("type", "string", "description", "Search query string."));
        properties.put("top_k", Map.of("type", "integer", "description", "Max number of results to return (1..10)."));
        properties.put("time_range", Map.of("type", "string", "enum",
                java.util.List.of("year", "month", "week", "all"),
                "description", "Restrict results to a recency window."));
        properties.put("language", Map.of("type", "string", "enum",
                java.util.List.of("zh", "en", "any"),
                "description", "Preferred result language."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", java.util.List.of("query"));
        return schema;
    }

    /**
     * Asserts that the mandatory LLM settings ({@code api-key} / {@code api-base} /
     * {@code model-name}) have been supplied. Called by the runtime wrapper during
     * bean initialisation so misconfiguration surfaces before the first request.
     *
     * @throws IllegalStateException if any of the required properties is blank
     */
    public void requireConfigured() {
        requireText(apiKey, "search-agent.llm.api-key");
        requireText(apiBase, "search-agent.llm.api-base");
        requireText(modelName, "search-agent.llm.model-name");
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(propertyName + " is required");
        }
    }

    /**
     * @return the agent id
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * @param agentId the agent id
     */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /**
     * @return the agent name
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * @param agentName the agent name
     */
    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    /**
     * @return the agent description
     */
    public String getAgentDescription() {
        return agentDescription;
    }

    /**
     * @param agentDescription the agent description
     */
    public void setAgentDescription(String agentDescription) {
        this.agentDescription = agentDescription;
    }

    /**
     * @return the LLM provider name
     */
    public String getProvider() {
        return provider;
    }

    /**
     * @param provider the LLM provider name
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * @return the LLM API key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * @param apiKey the LLM API key
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * @return the LLM base URL
     */
    public String getApiBase() {
        return apiBase;
    }

    /**
     * @param apiBase the LLM base URL
     */
    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    /**
     * @return the LLM model name
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * @param modelName the LLM model name
     */
    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    /**
     * @return whether SSL verification is enabled
     */
    public boolean isSslVerify() {
        return isSslVerify;
    }

    /**
     * @param isSslVerify whether SSL verification is enabled
     */
    public void setSslVerify(boolean isSslVerify) {
        this.isSslVerify = isSslVerify;
    }

    /**
     * @return the sampling temperature
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * @param temperature the sampling temperature
     */
    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    /**
     * @return the nucleus sampling probability
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * @param topP the nucleus sampling probability
     */
    public void setTopP(Double topP) {
        this.topP = topP;
    }

    /**
     * @return the ReAct max iterations
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * @param maxIterations the ReAct max iterations
     */
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    /**
     * @return the internal operation id used for tracing
     */
    public String getSysOperationId() {
        return sysOperationId;
    }

    /**
     * @param sysOperationId the internal operation id used for tracing
     */
    public void setSysOperationId(String sysOperationId) {
        this.sysOperationId = sysOperationId;
    }

    /**
     * @return whether the stub web-search backend is enabled
     */
    public boolean isUseStub() {
        return isUseStub;
    }

    /**
     * @param isUseStub whether the stub web-search backend is enabled
     */
    public void setUseStub(boolean isUseStub) {
        this.isUseStub = isUseStub;
    }

    /**
     * @return the search-agent system prompt
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * @param systemPrompt the search-agent system prompt
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
