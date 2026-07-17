/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.ExploreToolRegistrar;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.edpa.explore.LlmExplorer;
import com.openjiuwen.agents.edpa.mcp.McpClient;
import com.openjiuwen.agents.edpa.mcp.McpTool;
import com.openjiuwen.agents.edpa.mcp.McpToolRegistrar;
import com.openjiuwen.agents.edpa.mcp.StdioMcpClient;
import com.openjiuwen.agents.edpa.rail.UserInputCaptureRail;
import com.openjiuwen.agents.edpa.util.LlmResponseExtractor;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Real-LLM + real-MCP e2e: EDPA-alpha produces a Morgan Stanley-style
 * investment research report using free MCP servers.
 *
 * <p>Tests the full E→D→P→A loop with real financial data from SEC EDGAR:
 * <ul>
 *   <li><b>E</b>xplore: LLM calls explore tool → LlmExplorer gathers analysis angles</li>
 *   <li><b>D</b>ecision: tool failures or stale data → __replan__ → PevKernel dispatch</li>
 *   <li><b>P</b>lan: 7-section MS report structure is the execution plan</li>
 *   <li><b>A</b>ction: report verified by CriteriaReplanBridgeRail against MS section keywords</li>
 * </ul>
 *
 * <p><b>MCP transport — the LLMClient-style workaround.</b> The SDK's MCP
 * client (StdioClient hangs / StreamableHTTP drops Mcp-Session-Id / SSE returns
 * 0 tools) is bypassed: {@link StdioMcpClient} speaks JSON-RPC over the
 * subprocess stdin/stdout directly, and {@link McpToolRegistrar} registers
 * native {@code Tool} adapters. The agent sees a normal multi-tool surface.
 *
 * <p>MCP server (free, stdio, no API key): SEC EDGAR (sec-edgar-mcp) — 10-K
 * filings + financial statements. Install: {@code pip install sec-edgar-mcp}.
 *
 * <p>Env-gated (skip unless ALL required env vars present):
 * <ul>
 *   <li>{@code OPENJIUWEN_API_KEY} + {@code OPENJIUWEN_BASE_URL} — LLM access</li>
 *   <li>{@code EDPA_MCP_E2E_ENABLED=true} — explicit opt-in (MCP spawns subprocesses)</li>
 * </ul>
 *
 * @since 2026-07
 */
class McpInvestmentResearchE2eTest {
    /** Morgan Stanley 7-section report structure — task prompt. */
    static final String INVESTMENT_RESEARCH_TASK = """
            你是一名遵循摩根士丹利（Morgan Stanley）投资顾问范式的投研分析师。
            请对【苹果公司（AAPL，SEC CIK=0000320193）】完成一份完整的投资研究报告。

            【工作流程 — 必须按此顺序】
            1. 探索（Explore）：先调用一次 explore 工具，识别本次分析应覆盖的维度。
            2. 数据采集：调用 SEC EDGAR MCP 工具获取真实数据（用 CIK 0000320193 更快更稳）：
               - get_financials(identifier="0000320193")：营收、净利润、现金流
               - get_company_info(identifier="0000320193")：公司基本信息
               - get_recent_filings(identifier="0000320193", form_type="10-K")：最新年报
            3. 如发现当前分析方向需调整，调用 __replan__ 工具说明原因与新方向。
            4. 撰写报告：按以下摩根士丹利报告结构输出（每节必须有实质内容，不可省略）。

            【效率约束 — 重要】
            - 每个工具最多调用一次，不要重复调用同一工具。
            - 数据采集阶段总工具调用不超过 5 次，获取到数据后立即进入撰写。
            - 不要反复确认同一信息；基于已采集数据直接撰写报告。

            【最终回复规则 — 极其重要，违反将导致任务失败】
            - 本系统遵循 ReAct 协议：你输出的任何【不含工具调用】的回复都会被立即当作最终结果返回，循环就此结束。
            - 因此：采集数据时务必带上工具调用；一旦你输出不含工具调用的纯文本，那就是最终报告。
            - 绝对禁止输出"现在开始撰写报告""接下来我将分析"之类的过渡句而不带工具调用——
              这会被系统当作残缺的最终结果直接收口，报告只剩一句话。
            - 正确做法：调用完最后一个工具后，在下一条回复里【直接写出完整 7 段报告正文】，
              不要插任何过渡句，第一行就是"## 一、执行摘要"。

            【报告结构 — 摩根士丹利范式】
            ## 一、执行摘要（Executive Summary）
            包含：投资评级建议（买入/持有/卖出）、目标价、核心逻辑一句话概括。
            ## 二、市场环境概述（Market Overview）
            宏观经济环境、利率环境、市场情绪对科技板块的影响。
            ## 三、行业分析（Sector Analysis）
            科技行业/消费电子行业趋势、竞争格局、AAPL 的行业地位。
            ## 四、公司分析（Company Analysis）
            基于采集的真实财务数据：营收趋势、盈利能力、现金流状况、估值指标（P/E、P/S）。
            ## 五、投资逻辑（Investment Thesis）
            清晰的多点投资论点，每点有数据支撑。
            ## 六、风险评估（Risk Assessment）
            关键风险因素（至少3个）及缓释措施。
            ## 七、估值与建议（Valuation & Recommendation）
            估值方法（至少提及 DCF 或可比公司法）、目标价、评级。

            【约束】
            - 报告必须基于 MCP 工具采集的真实数据，不可编造财务数字。
            - 如某 MCP 工具不可用，在对应章节标注数据缺失并说明影响。
            """;

    /** MS section keywords for CriteriaVerifier — each criterion must have ≥half keywords present. */
    static final List<String> RESEARCH_CRITERIA = List.of("执行摘要 投资评级 目标价", "市场环境 宏观 利率",
            "行业分析 竞争格局 行业地位", "公司分析 营收 净利润 估值", "投资逻辑 论点 数据支撑", "风险评估 风险因素 缓释",
            "估值 建议 评级 DCF 可比");

    /** MS section headers for structural assertion. */
    static final List<String> MS_SECTIONS = List.of("执行摘要", "市场环境", "行业分析", "公司分析", "投资逻辑", "风险评估", "估值");

    private static final Logger LOG = Logger.getLogger(McpInvestmentResearchE2eTest.class.getName());

    /**
     * Runs the full EDPA investment-research e2e against a real LLM and the SEC EDGAR
     * MCP server, then hard-asserts the integration mechanics (data-channel proof) and
     * a minimum Morgan Stanley report structure.
     *
     * @throws Exception if the agent invoke or MCP lifecycle throws unexpectedly
     */
    @Test
    @Timeout(600)
    void edpaMcpInvestmentResearch_producesMsStyleReport() throws Exception {
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        String mcpEnabled = System.getenv("EDPA_MCP_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                key != null && !key.isBlank() && base != null && !base.isBlank() && "true".equalsIgnoreCase(mcpEnabled),
                "EDPA MCP e2e requires OPENJIUWEN_API_KEY / OPENJIUWEN_BASE_URL / EDPA_MCP_E2E_ENABLED=true");

        String modelName = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash");
        DefaultModelClientFactories.ensureRegistered();

        ReActAgent agent = buildAgent(key, base, modelName);
        CountingMcpClient mcpClient = startAndRegisterMcp(agent);
        try {
            ExplorationHarness exploration = wireRails(agent, base, key, modelName);

            LOG.log(Level.INFO, "[mcp-research] Starting EDPA investment research e2e...");
            Object result = agent.invoke(INVESTMENT_RESEARCH_TASK, null);

            String output = String.valueOf(result);
            int previewLen = Math.min(500, output.length());
            LOG.log(Level.INFO, "[mcp-research] output length: {0,number,#}", output.length());
            LOG.log(Level.INFO, "[mcp-research] output preview: {0}", output.substring(0, previewLen));

            assertIntegrationMechanics(result, output, mcpClient, exploration.userInputRef());
            runSoftObservations(result, output, exploration.exploreCallCount(), mcpClient);

            long sectionsPresent = MS_SECTIONS.stream().filter(output::contains).count();
            LOG.log(Level.INFO, "[mcp-research] MS sections present: {0,number,#}/7", sectionsPresent);
            assertThat(sectionsPresent).as("Report must contain at least 4 of 7 Morgan Stanley sections — "
                    + "a research agent must produce a structured report, not just tool calls (got " + sectionsPresent
                    + ")").isGreaterThanOrEqualTo(4);
            // replanCount/recentToolFailureNodes 观察移除：状态隔离后（RailInvocationState）invoke 返回 ctx 不可达，
            // rail 操作状态 invoke 后读不到（状态隔离代价，诚实标注）。承重靠 mock 单测。
        } finally {
            mcpClient.close();
        }
    }

    /**
     * Builds the {@link ReActAgent} with an LLM wired to the OpenAI-compatible endpoint
     * and the per-model thinking-mode flag applied to the request config.
     *
     * @param key       API key for the LLM endpoint
     * @param base      base URL of the OpenAI-compatible LLM endpoint
     * @param modelName model name to invoke
     * @return a configured ReActAgent whose iteration budget is bumped for MCP-heavy work
     */
    private ReActAgent buildAgent(String key, String base, String modelName) {
        var cliCfg = ModelClientConfig.builder().clientId("edpa-mcp-research-" + System.nanoTime())
                .clientProvider("OpenAI").apiKey(key).apiBase(base).verifySsl(false).timeout(120000).build();
        var reqCfg = ModelRequestConfig.builder().modelName(modelName).temperature(0.3).topP(0.9).maxTokens(4000)
                .build();
        configureThinkingMode(reqCfg);
        ToolCallingEnforcingModel model = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-mcp-research").build());
        agent.setLlm(model);

        // Bump the ReAct iteration budget — default is 5, far too small for an MCP-heavy
        // task. Mutate in place to preserve all other config defaults. @Timeout caps wall-clock.
        Object cfgObj = agent.getConfig();
        if (cfgObj instanceof ReActAgentConfig reactCfg) {
            reactCfg.configureMaxIterations(30);
        }
        return agent;
    }

    /**
     * Applies the per-model thinking-mode flag from {@code LLM_THINKING} env var
     * (used by the 6-model e2e matrix). Defaults to disabled thinking.
     *
     * @param reqCfg the request config to mutate with the chosen extra field
     */
    private static void configureThinkingMode(ModelRequestConfig reqCfg) {
        String tMode = System.getenv().getOrDefault("LLM_THINKING", "glm-off");
        switch (tMode) {
            case "qwen-off" -> reqCfg.setExtraField("enable_thinking", false);
            case "qwen-on" -> reqCfg.setExtraField("enable_thinking", true);
            case "thinking-on" -> reqCfg.setExtraField("thinking", Map.of("type", "enabled"));
            default -> reqCfg.setExtraField("thinking", Map.of("type", "disabled"));
        }
    }

    /**
     * Spawns the SEC EDGAR MCP subprocess via the thin stdio JSON-RPC client and
     * registers its tools onto the agent. Skips the e2e (Assumption) if the MCP
     * server binary is unavailable or exposes no tools.
     *
     * @param agent the agent whose tool surface should receive the MCP tools
     * @return a counting wrapper around the raw client (for data-channel assertions)
     * @throws Exception if MCP tool registration fails with a non-transport error
     *                   ({@link McpToolRegistrar#registerOnto} declares a broad
     *                   {@code throws Exception}); propagated to the test method.
     */
    private CountingMcpClient startAndRegisterMcp(ReActAgent agent) throws Exception {
        // ---- MCP via thin stdio JSON-RPC client (SDK MCP client workaround) ----
        StdioMcpClient rawClient = new StdioMcpClient(List.of("python3", "-m", "sec_edgar_mcp.server"),
                Map.of("SEC_EDGAR_USER_AGENT",
                        System.getenv().getOrDefault("SEC_EDGAR_USER_AGENT", "EDPA Research e2e test@example.com")));
        // Counting wrapper so the e2e can hard-assert the LLM dispatched real MCP tools
        // through our thin client (end-to-end data-channel proof).
        CountingMcpClient mcpClient = new CountingMcpClient(rawClient);
        try {
            rawClient.start();
            Set<String> allMcpToolNames = McpToolRegistrar
                    .registeredNames(McpToolRegistrar.registerOnto(agent, mcpClient));
            org.junit.jupiter.api.Assumptions.assumeTrue(!allMcpToolNames.isEmpty(),
                    "No MCP tools exposed — server connected but listed 0 tools. Skipping.");
            LOG.log(Level.INFO, "[mcp-research] MCP tools available ({0,number,#}): {1}",
                    new Object[] {allMcpToolNames.size(), allMcpToolNames});
            return mcpClient;
        } catch (IOException e) {
            // CodeCheck: e2e test 容错 — subprocess spawn / JSON-RPC transport 失败时优雅 skip
            // （pip install sec-edgar-mcp 未装 / 网络不通），而非让测试硬失败。
            rawClient.close();
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "SEC EDGAR MCP server unavailable (" + e.getMessage() + "), skip e2e — "
                            + "install: pip install sec-edgar-mcp");
            return mcpClient;
        }
    }

    /**
     * Registers the EDPA rails (user-input capture, explorer, criteria/replan/self-heal)
     * onto the agent and returns the mutable harness handles the test observes.
     *
     * @param agent      the agent to wire rails onto
     * @param base       base URL of the LLM endpoint (for the explorer HTTP fn)
     * @param key        API key for the LLM endpoint
     * @param modelName  model name for the explorer LLM call
     * @return the exploration harness holding the user-input ref and explore-call counter
     */
    private ExplorationHarness wireRails(ReActAgent agent, String base, String key, String modelName) {
        AtomicReference<String> userInputRef = new AtomicReference<>();
        agent.registerRail(new UserInputCaptureRail(userInputRef));

        AtomicInteger exploreCallCount = new AtomicInteger(0);
        Function<String, String> explorerFn = buildExplorerLlmFn(base, key, modelName,
                System.getenv().getOrDefault("OPENJIUWEN_COMPLETIONS_PATH", "/chat/completions"), exploreCallCount);
        Explorer explorer = new LlmExplorer(explorerFn, ExploreBudget.DEFAULT);
        ExploreToolRegistrar.registerOnto(agent, explorer, ExploreBudget.DEFAULT, () -> userInputRef.get());

        ReplanRail sharedCounter = new ReplanRail(3);
        agent.registerRail(
                new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(), RESEARCH_CRITERIA, sharedCounter));

        ReplanRail pevReplanRail = new ReplanRail(3);
        agent.registerRail(pevReplanRail);
        ReplanTool.registerOnto(agent);

        agent.registerRail(new RootCauseRail());
        return new ExplorationHarness(userInputRef, exploreCallCount);
    }

    /**
     * Hard-asserts the integration mechanics — the data-channel proof that the agent
     * actually drove real MCP tools and captured the user query.
     *
     * @param result       the agent invoke result
     * @param output       stringified result
     * @param mcpClient    the counting MCP client
     * @param userInputRef the captured user-input reference
     */
    private static void assertIntegrationMechanics(Object result, String output, CountingMcpClient mcpClient,
            AtomicReference<String> userInputRef) {
        // ---- HARD assertions: integration mechanics (E2E data-channel proof) ----
        assertThat(result).as("EDPA loop must produce a non-null result").isNotNull();
        assertThat(output).as("EDPA loop must produce non-empty output (data channel ran)").isNotEmpty();
        assertThat(mcpClient.callCount())
                .as("LLM must dispatch >=1 real MCP tool through the thin stdio client "
                        + "(end-to-end: agent -> Tool adapter -> McpClient -> SEC EDGAR)")
                .isGreaterThanOrEqualTo(1);
        assertThat(userInputRef.get()).as("UserInputCaptureRail must capture the user query").isNotNull().isNotEmpty();
    }

    /**
     * Emits SOFT observations — LLM-behavior-dependent, non-deterministic signals that
     * inform but never fail the test.
     *
     * @param result           the agent invoke result
     * @param output           stringified result
     * @param exploreCallCount how many times the explorer LLM was called
     * @param mcpClient        the counting MCP client
     */
    private static void runSoftObservations(Object result, String output, AtomicInteger exploreCallCount,
            CountingMcpClient mcpClient) {
        // ---- SOFT observations (LLM-behavior dependent, non-deterministic) ----
        if (exploreCallCount.get() > 0) {
            LOG.log(Level.INFO, "[mcp-research] ✅ Explore phase activated (count={0,number,#})",
                    exploreCallCount.get());
        } else {
            LOG.log(Level.INFO, "[mcp-research] ⚠️ Explore tool not called (soft-observe)");
        }
        LOG.log(Level.INFO, "[mcp-research] MCP tools dispatched through thin client: {0,number,#}",
                mcpClient.callCount());
        LOG.log(Level.INFO, "[mcp-research] distinct MCP tools called: {0}", mcpClient.calledNames());
        if (output.contains("Max iterations reached")) {
            LOG.log(Level.INFO,
                    "[mcp-research] ⚠️ Loop hit max-iterations before finishing report (soft-observe; "
                            + "integration still proven by tool dispatch above)");
        }
        if (result instanceof Map<?, ?> resultMap) {
            logCriteriaVerifyResult(resultMap);
        }
    }

    /**
     * Logs the criteria-verify outcome (PASS / degraded FAIL) from the result map.
     *
     * @param resultMap the agent result map
     */
    private static void logCriteriaVerifyResult(Map<?, ?> resultMap) {
        Object verified = resultMap.get(CriteriaReplanBridgeRail.VERIFIED_KEY);
        if (Boolean.TRUE.equals(verified)) {
            LOG.log(Level.INFO, "[mcp-research] ✅ Criteria verify PASS — MS report structure complete");
            return;
        }
        if (Boolean.FALSE.equals(verified)) {
            LOG.log(Level.INFO, "[mcp-research] ⚠️ Criteria verify FAIL (degraded) — unmet: {0}",
                    resultMap.get(CriteriaReplanBridgeRail.UNMET_KEY));
        }
    }

    /**
     * Mutable handles the test observes across the EDPA loop (user-input ref + explore counter).
     *
     * @param userInputRef    atomic reference capturing the last user query
     * @param exploreCallCount counter of how many times the explorer LLM was invoked
     */
    private record ExplorationHarness(AtomicReference<String> userInputRef, AtomicInteger exploreCallCount) {
    }

    /**
     * Delegating McpClient that counts {@code tools/call} dispatches — lets the e2e
     * hard-assert that the LLM actually drove real MCP tools through our thin client.
     */
    static final class CountingMcpClient implements McpClient {
        private final McpClient delegate;
        private final AtomicInteger callCount = new AtomicInteger();
        private final Set<String> called = ConcurrentHashMap.newKeySet();

        /**
         * Wraps the given delegate to observe tool dispatches.
         *
         * @param delegate the underlying MCP client to delegate calls to
         */
        CountingMcpClient(McpClient delegate) {
            this.delegate = delegate;
        }

        /**
         * Lists available MCP tools from the delegate.
         *
         * @return the list of MCP tools exposed by the delegate
         * @throws Exception if the delegate fails to list tools
         */
        @Override
        public List<McpTool> listTools() throws Exception {
            return delegate.listTools();
        }

        /**
         * Calls a tool on the delegate and records the dispatch (name + count).
         *
         * @param name      the MCP tool name
         * @param arguments the tool arguments map
         * @return the tool result text returned by the delegate
         * @throws Exception if the delegate fails to call the tool
         */
        @Override
        public String callTool(String name, Map<String, Object> arguments) throws Exception {
            callCount.incrementAndGet();
            called.add(name);
            return delegate.callTool(name, arguments);
        }

        /**
         * Closes the delegate client and releases the subprocess.
         */
        @Override
        public void close() {
            delegate.close();
        }

        /**
         * Returns how many {@code tools/call} dispatches were observed by this wrapper.
         *
         * @return how many tool calls were dispatched through this wrapper
         */
        int callCount() {
            return callCount.get();
        }

        /**
         * Returns the distinct tool names that were dispatched through this wrapper.
         *
         * @return the set of distinct tool names that were called
         */
        Set<String> calledNames() {
            return called;
        }
    }

    /**
     * Builds the Explorer's LLM function — direct HTTP call (bypasses SDK Model.invoke
     * top_p parameter bug, same pattern as EdpaRealLlmE2eTest).
     *
     * @param base             base URL of the OpenAI-compatible LLM endpoint
     * @param key              API key for the LLM endpoint
     * @param modelName        model name to invoke
     * @param path             completions path (e.g. {@code /chat/completions})
     * @param exploreCallCount counter incremented on each explorer LLM call
     * @return a function mapping an explorer prompt to the LLM-generated analysis text
     */
    private static Function<String, String> buildExplorerLlmFn(String base, String key, String modelName, String path,
            AtomicInteger exploreCallCount) {
        return prompt -> {
            exploreCallCount.incrementAndGet();
            LOG.log(Level.INFO, "[mcp-research] Explorer LLM called (count={0,number,#})", exploreCallCount.get());
            try {
                String escaped = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                        .replace("\r", "");
                String body = "{\"model\":\"" + modelName + "\"," + "\"messages\":[{\"role\":\"user\",\"content\":\""
                        + escaped + "\"}]," + "\"temperature\":0.3,\"max_tokens\":800,\"top_p\":0.9}";
                var httpReq = HttpRequest.newBuilder().uri(URI.create(base + path))
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var httpResp = HttpClient.newHttpClient().send(httpReq, HttpResponse.BodyHandlers.ofString());
                if (httpResp.statusCode() != 200) {
                    LOG.log(Level.INFO, "[mcp-research] Explorer HTTP {0,number,#}", httpResp.statusCode());
                    return "";
                }
                return LlmResponseExtractor.extractContent(httpResp.body());
            } catch (IOException | InterruptedException e) {
                // CodeCheck: explorer LLM 是直接 HTTP 调用，IOException/InterruptedException 是唯一可能
                // 的受检失败（网络/中断）。包装成 IllegalStateException（带原因）由上层统一处理，
                // 因为 Function<String,String> 签名不允许抛出受检异常。
                throw new IllegalStateException("Explorer LLM call failed: " + e.getMessage(), e);
            }
        };
    }
}
