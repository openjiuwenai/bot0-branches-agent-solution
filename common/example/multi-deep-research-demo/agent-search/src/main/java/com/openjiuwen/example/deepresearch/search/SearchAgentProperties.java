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
 */
public class SearchAgentProperties {

    private String agentId = "search-agent";
    private String agentName = "SearchAgent";
    private String agentDescription = "ReAct web-search sub-agent (Tavily + source-kind reranker)";

    private String provider = "OpenAI";
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private String modelName = "deepseek-chat";
    private boolean sslVerify = true;
    private Double temperature = 0.2;
    private Double topP = 0.8;

    private int maxIterations = 4;
    private String sysOperationId = "deep-research-search-agent";

    /**
     * When {@code true}, the wrapper should wire {@link StubWebSearchTool#search}
     * as the tool body (fixture-backed). Default {@code false} = prod Tavily.
     */
    private boolean useStub = false;

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

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }

    public String getAgentDescription() { return agentDescription; }
    public void setAgentDescription(String agentDescription) { this.agentDescription = agentDescription; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getApiBase() { return apiBase; }
    public void setApiBase(String apiBase) { this.apiBase = apiBase; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public boolean isSslVerify() { return sslVerify; }
    public void setSslVerify(boolean sslVerify) { this.sslVerify = sslVerify; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }

    public String getSysOperationId() { return sysOperationId; }
    public void setSysOperationId(String sysOperationId) { this.sysOperationId = sysOperationId; }

    public boolean isUseStub() { return useStub; }
    public void setUseStub(boolean useStub) { this.useStub = useStub; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
}
