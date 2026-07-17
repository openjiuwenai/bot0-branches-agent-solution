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

            You have two tools:

            1. `ask_user({ question: string })` — ask the caller a single clarifying
               question. Emits an interrupt; the caller's next-turn text arrives back
               as the tool result. Use ONLY when the query is genuinely ambiguous in
               a way that would waste a web_search call (see the Ambiguity rules below).
            2. `web_search({ query: string, top_k: int, time_range: "year"|"month"|"week"|"all", language: "zh"|"en"|"any" })`
               — perform the actual search. Call exactly once per resolved user
               request unless the first call returns an empty `results` array — in
               that case, retry at most once with a reformulated query (drop vendor
               brand names, switch language) before giving up.

            Ambiguity rules (HARD — evaluate FIRST, before any web_search):
            If ANY of the following patterns match, you MUST call `ask_user` first
            and MUST NOT call `web_search` until you have the clarifying answer.
            Extra qualifier words in the query (e.g. "API", "官方", "官网文档",
            year numbers like "2025") DO NOT resolve these ambiguities — only a
            concrete model / SKU / version does.
            - Vendor + product family, no specific model / version / SKU. Example:
              "DeepSeek 官网报价" — you MUST ask which model (V2 / V2.5 / V3 /
              R1 / Coder / …) before searching, because "报价" only makes sense
              per-SKU. Same for "DeepSeek API 定价"、"DeepSeek 模型价格" — the
              vendor is fixed but the SKU is still missing, so ask.
            - Two or more plausible entities behind the same short name (e.g. "Claude"
              could mean the API tier or the consumer app subscription).
            - Time-sensitive phrases with no explicit period ("最新价格" but no
              year / quarter).
            Fallback: if NONE of the above patterns match, prefer `web_search`
            over `ask_user`. Do NOT invent new ambiguities beyond this list.

            Ask-user protocol:
            - Compose ONE concise Chinese question. Include the concrete options
              you'd otherwise have to disambiguate (e.g. "DeepSeek 有多款模型，请问要查
              V3、R1、V2.5、还是 Coder 的官网报价？").
            - Do NOT ask for multi-turn refinement — you get one round.
            - After the caller's answer arrives (as the ask_user tool result),
              use it verbatim as the resolved query, then call `web_search`.

            Output contract (must match exactly so the root agent can parse it):
              {
                "results": [
                  { "url": "...", "title": "...", "snippet": "...",
                    "source_kind": "official|blog|news|forum", "score": 0.0 }
                ]
              }

            Rules:
            - Once the query is unambiguous, pass it through verbatim — do not
              rewrite unless retrying an empty result.
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
     * Gets the agent id.
     *
     * @return the agent id
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * Sets the agent id.
     *
     * @param agentId the agent id
     */
    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    /**
     * Gets the agent name.
     *
     * @return the agent name
     */
    public String getAgentName() {
        return agentName;
    }

    /**
     * Sets the agent name.
     *
     * @param agentName the agent name
     */
    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    /**
     * Gets the agent description.
     *
     * @return the agent description
     */
    public String getAgentDescription() {
        return agentDescription;
    }

    /**
     * Sets the agent description.
     *
     * @param agentDescription the agent description
     */
    public void setAgentDescription(String agentDescription) {
        this.agentDescription = agentDescription;
    }

    /**
     * Gets the LLM provider name.
     *
     * @return the LLM provider name
     */
    public String getProvider() {
        return provider;
    }

    /**
     * Sets the LLM provider name.
     *
     * @param provider the LLM provider name
     */
    public void setProvider(String provider) {
        this.provider = provider;
    }

    /**
     * Gets the LLM API key.
     *
     * @return the LLM API key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * Sets the LLM API key.
     *
     * @param apiKey the LLM API key
     */
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    /**
     * Gets the LLM base URL.
     *
     * @return the LLM base URL
     */
    public String getApiBase() {
        return apiBase;
    }

    /**
     * Sets the LLM base URL.
     *
     * @param apiBase the LLM base URL
     */
    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    /**
     * Gets the LLM model name.
     *
     * @return the LLM model name
     */
    public String getModelName() {
        return modelName;
    }

    /**
     * Sets the LLM model name.
     *
     * @param modelName the LLM model name
     */
    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    /**
     * Returns whether SSL verification is enabled.
     *
     * @return whether SSL verification is enabled
     */
    public boolean isSslVerify() {
        return isSslVerify;
    }

    /**
     * Sets whether SSL verification is enabled.
     *
     * @param isSslVerify whether SSL verification is enabled
     */
    public void setSslVerify(boolean isSslVerify) {
        this.isSslVerify = isSslVerify;
    }

    /**
     * Gets the sampling temperature.
     *
     * @return the sampling temperature
     */
    public Double getTemperature() {
        return temperature;
    }

    /**
     * Sets the sampling temperature.
     *
     * @param temperature the sampling temperature
     */
    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    /**
     * Gets the nucleus sampling probability.
     *
     * @return the nucleus sampling probability
     */
    public Double getTopP() {
        return topP;
    }

    /**
     * Sets the nucleus sampling probability.
     *
     * @param topP the nucleus sampling probability
     */
    public void setTopP(Double topP) {
        this.topP = topP;
    }

    /**
     * Gets the ReAct max iterations.
     *
     * @return the ReAct max iterations
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Sets the ReAct max iterations.
     *
     * @param maxIterations the ReAct max iterations
     */
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    /**
     * Gets the internal operation id used for tracing.
     *
     * @return the internal operation id used for tracing
     */
    public String getSysOperationId() {
        return sysOperationId;
    }

    /**
     * Sets the internal operation id used for tracing.
     *
     * @param sysOperationId the internal operation id used for tracing
     */
    public void setSysOperationId(String sysOperationId) {
        this.sysOperationId = sysOperationId;
    }

    /**
     * Returns whether the stub web-search backend is enabled.
     *
     * @return whether the stub web-search backend is enabled
     */
    public boolean isUseStub() {
        return isUseStub;
    }

    /**
     * Sets whether the stub web-search backend is enabled.
     *
     * @param isUseStub whether the stub web-search backend is enabled
     */
    public void setUseStub(boolean isUseStub) {
        this.isUseStub = isUseStub;
    }

    /**
     * Gets the search-agent system prompt.
     *
     * @return the search-agent system prompt
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Sets the search-agent system prompt.
     *
     * @param systemPrompt the search-agent system prompt
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
