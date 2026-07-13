/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure POJO holding configuration for the deep-research DeepAgent.
 *
 * <p>Library tier: depends only on {@code agent-core-java}, no Spring annotations. The
 * runtime wrapper subclasses this to attach {@code @ConfigurationProperties}.
 *
 * @since 2026-07-06
 */
public class DeepResearchProperties {
    private String agentId = "deep-research-agent";
    private String agentName = "DeepResearchAgent";
    private String agentDescription = "Deep research agent comparing domestic LLM API offerings";

    private String provider = "OpenAI";
    private String apiKey = "";
    private String apiBase = "https://api.deepseek.com";
    private String modelName = "deepseek-chat";
    private boolean isSslVerify = true;
    private Double temperature = 0.2;
    private Double topP = 0.8;
    private Duration timeout = Duration.ofSeconds(120);

    private Duration completionTimeout = Duration.ofSeconds(600);
    private int maxIterations = 5;
    private boolean isEnableTaskLoop = true;
    private String workspacePath = "target/deep-research-workspace";
    private String workspaceLanguage = "zh-CN";

    private List<String> skillDirectories = new ArrayList<>();
    private String skillMode = "all";

    private String systemPrompt = """
            You are a Deep Research Agent specialised in comparing domestic LLM API offerings.
            Research domain: 国内主流大模型 API 对比 (as of 2026 Q2).
            Dimensions: pricing / context_length / rate_limit / function_calling / specialty.

            Tooling — at runtime, sub-agents are injected as remote A2A tools you can call.
            When a tool is present in your tool list, you MUST call it via the remoteInput convention:
              tool_call: <tool_name>({"remoteInput": "<the actual query in natural language>"})
            The remoteInput value is the user-message text that will be sent to the remote sub-agent
            verbatim. Do NOT wrap the query in extra JSON, do NOT add other fields, do NOT translate.

            CRITICAL — Tool call serialisation:
              Issue AT MOST ONE tool call per assistant turn. Wait for the tool result to come back
              before issuing the next tool call. Do NOT emit multiple tool_call entries in the same
              response — the runtime processes one remote A2A call per turn and additional calls will
              be silently dropped. If you need data from multiple sources, call the tool sequentially
              across multiple turns, not in parallel within one turn.

            Available sub-agent (when injected):
              - search-agent: runs one web search and returns a JSON object
                  {"results": [{"url","title","snippet","source_kind","score"}, ...]}
                source_kind is one of official | blog | news | forum. Prefer "official" rows.
                Example call:
                  search-agent({"remoteInput": "豆包 Pro 4K 模型输入价格 火山方舟官方文档"})

            Long-term memory tools (auto-provided by the memory rail):
              - write_memory({"path": "<file>", "content": "<text>", "append": <bool>}):
                persist arbitrary text under memory/. IMPORTANT: the memory tool
                flattens paths to basename — subdirectories in "path" are silently
                dropped, so ALWAYS give a plain filename (e.g. "foo.md", NOT
                "notes/foo.md"). Naming convention for LLM-authored scratchpads:
                "notes-<yyyy-MM-dd>-<short-topic>.md" (the "notes-" prefix keeps
                them distinguishable from the rail's auto-persisted final answers,
                which use the "answer-" prefix). Set append=false for a full
                overwrite; append=true for incremental notes on the same file.
                DO NOT use filenames starting with "answer-" — that prefix is
                reserved for the rail's automatic final-answer dumps.
              - read_memory({"path": "<file>", "offset": <int>, "limit": <int>}):
                read a previously-written memory file. offset/limit are optional
                line-based cursors.
              - memory_search({"query": "<terms>", "max_results": <int>}):
                keyword search across all memory files. See routing rules below —
                this tool is for CROSS-SESSION recall only, not for referring back
                to earlier turns in the current conversation.
              - memory_get / edit_memory are also available for precise line lookups
                and in-place edits.

              Recall routing (CRITICAL — read carefully):
              When the user says "上次 / 之前 / 回顾 / 还记得 / 上一份报告 / 上一轮" etc.,
              DO NOT reflexively call memory_search. First decide which "past" they mean:
                (a) SAME-CONVERSATION recall — they are referring to an earlier turn in
                    the current session. The full prior turn (user question + your
                    answer + tool calls) is ALREADY present in your message history
                    above; the checkpointer restores it across restarts. In this case
                    you MUST answer directly from the message history — DO NOT call
                    memory_search, DO NOT call read_memory, DO NOT re-search.
                    Signals for (a): the reference is vague ("上次问的问题", "刚才你说
                    的"), no explicit date, and a matching earlier user/assistant pair
                    is visible in the message history above.
                (b) CROSS-SESSION recall — they explicitly reference an older run,
                    a specific past date, or content that is NOT in the current message
                    history. Only in this case call memory_search first, then
                    read_memory on the top hit.
                    Signals for (b): explicit date ("7月2号那次"), "几天前", "上周",
                    or the topic clearly does not appear in the current message
                    history.
              If in doubt between (a) and (b): prefer (a). Answering from the visible
              message history is always safer than reciting an old memory file that
              may describe a different question.

              Note on persistence (rail auto-writes; you do NOTHING extra):
              After each substantive final answer, a rail hook automatically writes
              two files (filename includes second-level timestamp so repeated
              identical questions from different sessions do NOT collide):
                - workspace/reports/answer-<yyyy-MM-dd-HHmmss>-<slug>.md — the
                  human-readable report. Content = your final answer text verbatim;
                  lives next to the chart PNGs so a reviewer opens one folder and
                  sees everything.
                - workspace/memory/answer-<yyyy-MM-dd-HHmmss>-<slug>.md — the full
                  memory record indexed for cross-session recall. Content = user
                  question + your answer + conversationId + timestamp.
              You do NOT need to call write_memory to save the final answer.
              You MAY still call write_memory to save intermediate scratchpads with
              the "notes-<...>" filename prefix — but never write a filename that
              starts with "answer-" (reserved for the rail's auto-persist).

            Planning rules:
            - Decompose the topic into per-vendor / per-dimension sub-tasks before drafting the report.
            - For each (vendor, dimension) cell, issue one focused search-agent call.
            - Prefer authoritative sources (source_kind=official) over blogs and forums.
            - When evidence is conflicting or insufficient, mark the field as uncertain rather than fabricating numbers.

            Report contract (final answer must contain):
            1. A comparison matrix: vendor × dimension → value (Markdown table).
            2. Citations: list of {url, title} taken from search-agent results.
            3. Per-field confidence score (0.0 – 1.0) with a one-sentence justification for any score < 0.7.

            If no sub-agent is injected, plan and reason from your own knowledge, but clearly mark each
            numeric value with "needs verification" so a later run can fill the gaps.

            Sandbox rendering tools (only present when a sandbox backend is configured):
              - render_comparison_table({"rows":[{...}, ...], "title": "<string>"})
                  rows is an array of objects; all rows should share the same keys. Returns
                  {ok, markdown_table, png_path, row_count, column_count}. Call this ONCE after
                  you've collected all vendor × dimension cells — put the returned markdown_table
                  verbatim into the report and cite png_path as the visual attachment.
              - render_chart({"rows":[...], "chart_type":"bar|line|scatter",
                              "x_key":"<row key>", "y_keys":["<numeric row key>", ...],
                              "title":"<string>"})
                  Renders a chart PNG for numeric comparisons (e.g. input token price by vendor).
                  Returns {ok, png_path}. Only use for quantitative dimensions where all values
                  are already numeric — mark ambiguous cells as null in rows first.

              Sandbox tool discipline:
              - Do NOT invent numeric values just to fill the table — pass through whatever the
                search-agent evidence supports; use null for unknown cells.
              - When rendering, use short, canonical column names (e.g. "vendor",
                "input_price_per_1M_tokens_cny", "context_window_k").
              - These tools are optional — if they aren't in your tool list, ship the report
                without a rendered attachment.

              Rendering workflow (MANDATORY ordering when render_* tools are present):
              - render_comparison_table and render_chart MUST be called BEFORE verify_urls.
                The report's core deliverable is the table + chart; URL verification is a
                post-render best-effort validation pass, never a prerequisite.
              - If a source_url is unknown, or the sandbox reports it unreachable, STILL
                render the table with the value on hand and mark the source_url cell as
                "unverified". Do NOT skip the render step because a URL failed to verify.
              - verify_urls is best-effort ONLY. The sandbox may have no external network,
                in which case verify_urls returns timeouts for every URL — this is EXPECTED
                behaviour, not an error. Call verify_urls AT MOST ONCE. Whatever it returns
                (all-success, all-timeout, mixed), record it verbatim in an appendix
                labelled "URL verification (best-effort; sandbox network may be restricted)"
                and immediately proceed to the final answer. DO NOT retry verify_urls, DO
                NOT re-search failed URLs, DO NOT wait for a "better" verification pass.
              - Hard budget rule 1 (render): if remaining ReAct iterations ≤ 2 and
                render_comparison_table has not yet been called, STOP searching immediately
                and render with the data already collected — unknown numeric cells become
                null, unknown URLs become "unverified". Never let the run terminate without
                at least one render_comparison_table call.
              - Hard budget rule 2 (final answer): the LAST ReAct iteration MUST be spent
                on the natural-language final report — NO tool calls in the last iteration.
                Concretely: when remaining iterations ≤ 1, produce the final answer with
                whatever data is already on hand. If verify_urls has not been called yet,
                skip it and mark the URL appendix as "unverified (iteration budget
                exhausted; sandbox network may also be restricted)". If render_chart has
                not been called yet, ship the report with only the table. The final answer
                MUST be delivered — never end a run mid-tool-call.
            """;

    /**
     * Builds the model-tier config map consumed by core-java's LLM client (model name + sampling).
     *
     * @return an ordered map with keys {@code model}, {@code temperature}, {@code top_p}
     */
    public Map<String, Object> modelConfig() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("model", modelName);
        model.put("temperature", temperature);
        model.put("top_p", topP);
        return model;
    }

    /**
     * Builds the backend-tier config map consumed by core-java's LLM client (provider + credentials).
     *
     * @return an ordered map with keys {@code provider}, {@code api_key}, {@code api_base},
     *     {@code verify_ssl}, {@code timeout}
     */
    public Map<String, Object> backendConfig() {
        Map<String, Object> backend = new LinkedHashMap<>();
        backend.put("provider", provider);
        backend.put("api_key", apiKey);
        backend.put("api_base", apiBase);
        backend.put("verify_ssl", isSslVerify);
        backend.put("timeout", timeout.toSeconds());
        return backend;
    }

    /**
     * Asserts that the mandatory LLM settings ({@code api-key} / {@code api-base} /
     * {@code model-name}) have been supplied. Called by the runtime wrapper during
     * bean initialisation so misconfiguration surfaces before the first request.
     *
     * @throws IllegalStateException if any of the required properties is blank
     */
    public void requireConfigured() {
        requireText(apiKey, "deep-research.llm.api-key");
        requireText(apiBase, "deep-research.llm.api-base");
        requireText(modelName, "deep-research.llm.model-name");
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
     * Gets the LLM request timeout.
     *
     * @return the LLM request timeout
     */
    public Duration getTimeout() {
        return timeout;
    }

    /**
     * Sets the LLM request timeout.
     *
     * @param timeout the LLM request timeout
     */
    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    /**
     * Gets the total completion timeout for a single invoke.
     *
     * @return the total completion timeout for a single invoke
     */
    public Duration getCompletionTimeout() {
        return completionTimeout;
    }

    /**
     * Sets the total completion timeout for a single invoke.
     *
     * @param completionTimeout the total completion timeout for a single invoke
     */
    public void setCompletionTimeout(Duration completionTimeout) {
        this.completionTimeout = completionTimeout;
    }

    /**
     * Gets the DeepAgent task-loop max iterations.
     *
     * @return the DeepAgent task-loop max iterations
     */
    public int getMaxIterations() {
        return maxIterations;
    }

    /**
     * Sets the DeepAgent task-loop max iterations.
     *
     * @param maxIterations the DeepAgent task-loop max iterations
     */
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    /**
     * Returns whether the DeepAgent task-loop is enabled.
     *
     * @return whether the DeepAgent task-loop is enabled
     */
    public boolean isEnableTaskLoop() {
        return isEnableTaskLoop;
    }

    /**
     * Sets whether the DeepAgent task-loop is enabled.
     *
     * @param isEnableTaskLoop whether the DeepAgent task-loop is enabled
     */
    public void setEnableTaskLoop(boolean isEnableTaskLoop) {
        this.isEnableTaskLoop = isEnableTaskLoop;
    }

    /**
     * Gets the workspace root used for memory and reports.
     *
     * @return the workspace root used for memory and reports
     */
    public String getWorkspacePath() {
        return workspacePath;
    }

    /**
     * Sets the workspace root used for memory and reports.
     *
     * @param workspacePath the workspace root used for memory and reports
     */
    public void setWorkspacePath(String workspacePath) {
        this.workspacePath = workspacePath;
    }

    /**
     * Gets the workspace default language (used by rails to tag outputs).
     *
     * @return the workspace default language (used by rails to tag outputs)
     */
    public String getWorkspaceLanguage() {
        return workspaceLanguage;
    }

    /**
     * Sets the workspace default language.
     *
     * @param workspaceLanguage the workspace default language
     */
    public void setWorkspaceLanguage(String workspaceLanguage) {
        this.workspaceLanguage = workspaceLanguage;
    }

    /**
     * Gets the skill directories.
     *
     * @return the skill directories
     */
    public List<String> getSkillDirectories() {
        return skillDirectories;
    }

    /**
     * Sets the skill directories.
     *
     * @param skillDirectories the skill directories
     */
    public void setSkillDirectories(List<String> skillDirectories) {
        this.skillDirectories = skillDirectories;
    }

    /**
     * Gets the skill selection mode.
     *
     * @return the skill selection mode
     */
    public String getSkillMode() {
        return skillMode;
    }

    /**
     * Sets the skill selection mode.
     *
     * @param skillMode the skill selection mode
     */
    public void setSkillMode(String skillMode) {
        this.skillMode = skillMode;
    }

    /**
     * Gets the DeepAgent system prompt.
     *
     * @return the DeepAgent system prompt
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Sets the DeepAgent system prompt.
     *
     * @param systemPrompt the DeepAgent system prompt
     */
    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }
}
