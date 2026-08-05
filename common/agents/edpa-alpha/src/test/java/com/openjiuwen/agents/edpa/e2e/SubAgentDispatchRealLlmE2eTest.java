/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.mcp.McpClient;
import com.openjiuwen.agents.edpa.mcp.McpTool;
import com.openjiuwen.agents.edpa.mcp.McpToolAdapter;
import com.openjiuwen.agents.edpa.mcp.McpToolRegistrar;
import com.openjiuwen.agents.edpa.mcp.StdioMcpClient;
import com.openjiuwen.agents.edpa.subagent.SubAgentDispatcher.SubAgentExecutor;
import com.openjiuwen.agents.edpa.subagent.SubAgentDispatcher;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Real-LLM e2e for {@link SubAgentDispatcher} — proves the AgentCard-as-tool
 * dispatch chain end-to-end with a real LLM, which the mock-only
 * {@code SubAgentDispatcherTest} cannot (same lesson as MCP: mock proves control
 * flow, only a real run proves the data channel).
 *
 * <p><b>Chain under test (v1 — control flow first):</b>
 * <pre>
 *   host LLM  --calls--> delegate_analysis tool
 *                      --> SubAgentExecutor
 *                      --> subAgent.invoke(subGoal)   [a SECOND real ReActAgent]
 *                      --> sub-agent LLM produces analysis
 *                      --> result fans back as the tool result to the host
 *   host LLM  --incorporates--> final brief advice
 * </pre>
 *
 * <p><b>resourceMgr isolation finding (documented, not asserted):</b>
 * {@code Runner.resourceMgr()} is a process-global singleton — all Tool objects
 * (host's + sub-agent's) share one {@code idToCard} namespace. Dispatch is
 * global; only LLM-visibility (AbilityManager) is per-agent. So a sub-agent can
 * REUSE the host's already-registered tools by gaining AbilityManager visibility,
 * but there is NO tool isolation between agents. This test probes that.
 *
 * <p>v1 keeps the sub-agent LLM-only (no tools) to prove the dispatch chain in
 * isolation. Adding MCP to the sub-agent (reusing the host's global adapters)
 * is the v2 follow-on.
 *
 * <p>Env-gated: skips unless {@code OPENJIUWEN_API_KEY} /
 * {@code OPENJIUWEN_BASE_URL} / {@code EDPA_SUBAGENT_E2E_ENABLED=true} are set.
 *
 * @since 2026-07
 */
class SubAgentDispatchRealLlmE2eTest {
    /** Host task: delegate financial analysis to the sub-agent, then write brief advice. */
    static final String HOST_TASK = """
            你是一名投资研究编排者（orchestrator）。请完成以下任务：

            1. 调用 delegate_analysis 子任务工具，sub_goal 设为：
               "分析苹果公司（AAPL）的营收增长趋势，给出 3 句话的核心结论"。
               该工具会把任务交给一个专门的子智能体执行并返回结论。
            2. 基于子任务返回的结论，写一段不超过 100 字的投资建议。
               建议里要体现子任务返回的内容（可转述，但必须基于子任务结论）。

            【最终回复规则】
            - 调用 delegate_analysis 时带上工具调用。
            - 收到子任务结果后，你的下一条回复（无工具调用）就是最终投资建议，直接写正文，不要写"现在我来总结"之类的过渡句。
            """;

    /** v2 host task: delegate a get_financials-driven deep-dive to the sub-agent. */
    static final String V2_HOST_TASK = """
            你是投资研究编排者。完成以下任务：
            1. 调用 delegate_analysis 子任务工具，sub_goal 设为：
               "用 get_financials 工具查询 identifier=0000320193 的财报，分析苹果营收趋势，给出 3 句结论"。
            2. 基于子任务返回的结论，写一段不超过 100 字的投资建议。
            【规则】你自己不要直接调 get_financials，必须委派给子任务工具。收到子结果后，下一条无工具调用的回复即为最终建议，直接写正文。
            """;

    private static final Logger LOG = Logger.getLogger(SubAgentDispatchRealLlmE2eTest.class.getName());

    /** Shared env-gate assumption message shown when the real-LLM e2e credentials are absent. */
    private static final String ENV_GATE_MSG =
            "SubAgent e2e requires OPENJIUWEN_API_KEY / OPENJIUWEN_BASE_URL / EDPA_SUBAGENT_E2E_ENABLED=true";

    /** v2 env-gate assumption message (shared MCP variant). */
    private static final String ENV_GATE_V2_MSG =
            "SubAgent v2 e2e requires OPENJIUWEN_API_KEY / OPENJIUWEN_BASE_URL / EDPA_SUBAGENT_E2E_ENABLED=true";

    /**
     * v1 control-flow e2e: host delegates analysis to the sub-agent tool, the sub-agent runs a second
     * real ReActAgent, and the result fans back as the tool result to the host for the final advice.
     *
     * @throws Exception if the host or sub-agent invoke fails, or MCP/LLM setup throws
     */
    @Test
    @Timeout(300)
    void hostDelegatesToSubAgent_subAgentRunsResultFansBack() throws Exception {
        assumeEnvGated(ENV_GATE_MSG);
        String modelName = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash");
        DefaultModelClientFactories.ensureRegistered();

        // ---- sub-agent: a SECOND real ReActAgent (LLM-only, focused analysis) ----
        ReActAgent subAgent = new ReActAgent(AgentCard.builder().name("edpa-sub-financial").build());
        subAgent.setLlm(buildModel(envKey(), envBase(), modelName, "subagent", 800));
        bumpIterations(subAgent, 8);

        AtomicInteger subInvokeCount = new AtomicInteger(0);
        AtomicInteger subResultLen = new AtomicInteger(0);
        AtomicInteger subResultOverflow = new AtomicInteger(0);
        SubAgentExecutor executor = newSubAgentExecutor(subAgent, subInvokeCount, subResultLen, subResultOverflow);

        ReActAgent host = newHost(modelName, "host", "edpa-host-orchestrator", 1200, 15);
        SubAgentDispatcher.registerOnto(host, "delegate_analysis",
                "把一个分析子任务委派给专门的财务分析子智能体执行，返回其分析结论。", executor);

        // resourceMgr probe AFTER registration — confirms host's tool landed in the global namespace
        Object delegateTool = Runner.resourceMgr().getTool("delegate_analysis");
        logResourceMgrProbe(delegateTool);

        LOG.log(Level.INFO, "{0}", "[subagent-e2e] host invoking task...");
        Object result = host.invoke(HOST_TASK, null);
        String hostOutput = extractOutput(result);
        logV1OutputDiag(hostOutput, subInvokeCount, subResultLen);

        assertDispatchChain(result, hostOutput, subInvokeCount, subResultLen);
        assertThat(delegateTool)
                .as("SubAgentDispatcher.registerOnto must register the tool into the global resourceMgr").isNotNull();

        // ---- SOFT observations ----
        if (subResultOverflow.get() == 1) {
            LOG.log(Level.INFO, "{0}", "[subagent-e2e] ✅ sub-agent produced rich analysis (>200 chars)");
        }
        LOG.log(Level.INFO, "{0}",
                "[subagent-e2e] ✅ dispatch chain live: host -> delegate_analysis -> "
                        + "subAgent.invoke -> LLM -> fan-back");
    }

    /**
     * v2: the sub-agent drives a shared MCP tool. The host registers the SEC EDGAR MCP
     * tools (they land in the global resourceMgr); the sub-agent gains AbilityManager
     * visibility to {@code get_financials} only (reusing the host's global adapter — no
     * re-registration, no isolation). The host delegates the financial deep-dive; the
     * sub-agent must call {@code get_financials} and its dispatch must resolve through the
     * global resourceMgr to the shared MCP adapter.
     *
     * <p><b>Bearing question this answers:</b> does a sub-agent's tool dispatch actually
     * reach the host-registered global MCP adapter and fetch real SEC data? If yes, the
     * "orchestrator + specialist sharing one MCP tool pool" pattern is sound. If no, a
     * tool-resolution bug in the sub-agent context is exposed (mock-test-invisible).
     *
     * @throws Exception if the host or sub-agent invoke fails, or MCP start/registration throws
     */
    @Test
    @Timeout(600)
    void hostDelegatesToSubAgent_subAgentUsesSharedMcpTool() throws Exception {
        assumeEnvGated(ENV_GATE_V2_MSG);
        String modelName = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash");
        DefaultModelClientFactories.ensureRegistered();

        // ---- shared MCP client (thin stdio JSON-RPC, counting wrapper proves dispatch) ----
        CountingMcpClient mcp = startCountingMcpOrSkip();

        ReActAgent host = newHost(modelName, "host-v2", "edpa-host-v2", 1500, 15);

        // host registers ALL MCP tools globally (resourceMgr singleton)
        McpToolAdapter financialsAdapter = registerMcpAndFindFinancials(host, mcp);

        // sub-agent: gains AbilityManager visibility to get_financials ONLY — reuses the host's
        // global adapter, does NOT re-register (would collide in the shared namespace).
        ReActAgent subAgent = newSubAgentWithFinancials(modelName, financialsAdapter);

        AtomicInteger subInvokeCount = new AtomicInteger(0);
        AtomicInteger subResultLen = new AtomicInteger(0);
        SubAgentExecutor executor = newSubAgentExecutor(subAgent, subInvokeCount, subResultLen, null);
        SubAgentDispatcher.registerOnto(host, "delegate_analysis",
                "把一个需要调用 get_financials 工具的财务深挖任务委派给子智能体。", executor);

        LOG.log(Level.INFO, "{0}", "[subagent-v2] host invoking task (sub-agent should drive get_financials)...");
        Object result = host.invoke(V2_HOST_TASK, null);
        String hostOutput = extractOutput(result);
        logV2OutputDiag(hostOutput, subInvokeCount, mcp);

        assertSharedMcpChain(result, hostOutput, subInvokeCount, subResultLen, mcp);
    }

    /**
     * Guards both e2e tests behind the env gate; aborts via JUnit assumption when the required
     * env vars are absent so the suite is a no-op in CI without real-LLM credentials.
     *
     * @param message assumption message shown when the gate is not satisfied
     */
    private static void assumeEnvGated(String message) {
        String key = envKey();
        String base = envBase();
        String enabled = System.getenv("EDPA_SUBAGENT_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                key != null && !key.isBlank() && base != null && !base.isBlank() && "true".equalsIgnoreCase(enabled),
                message);
    }

    /**
     * Reads the OpenJiuwen API key from the environment for the real-LLM e2e.
     *
     * @return the OpenJiuwen API key from the environment, may be {@code null} (env-gated)
     */
    private static String envKey() {
        return System.getenv("OPENJIUWEN_API_KEY");
    }

    /**
     * Reads the OpenJiuwen API base URL from the environment for the real-LLM e2e.
     *
     * @return the OpenJiuwen API base URL from the environment, may be {@code null} (env-gated)
     */
    private static String envBase() {
        return System.getenv("OPENJIUWEN_BASE_URL");
    }

    /**
     * Builds a host ReActAgent wired to an enforcing model and bumped iteration cap.
     *
     * @param modelName LLM model name
     * @param role role tag used to namespace the model client id
     * @param cardName AgentCard name for the host
     * @param maxTokens maxTokens cap for the host model
     * @param iterations ReAct max-iterations cap
     * @return the configured host agent
     */
    private static ReActAgent newHost(String modelName, String role, String cardName, int maxTokens, int iterations) {
        ReActAgent host = new ReActAgent(AgentCard.builder().name(cardName).build());
        host.setLlm(buildModel(envKey(), envBase(), modelName, role, maxTokens));
        bumpIterations(host, iterations);
        return host;
    }

    /**
     * Registers all MCP tools onto the host (global resourceMgr) and resolves the {@code get_financials}
     * adapter, skipping the test if that tool is not exposed.
     *
     * @param host the host agent to register onto
     * @param mcp the counting MCP client
     * @return the get_financials adapter (never {@code null} — skips the test otherwise)
     * @throws Exception if MCP registration or tool listing fails
     */
    private static McpToolAdapter registerMcpAndFindFinancials(ReActAgent host, CountingMcpClient mcp)
            throws Exception {
        List<McpToolAdapter> adapters = McpToolRegistrar.registerOnto(host, mcp);
        McpToolAdapter financialsAdapter = adapters.stream().filter(a -> "get_financials".equals(a.mcpToolName()))
                .findFirst().orElse(null);
        org.junit.jupiter.api.Assumptions.assumeTrue(financialsAdapter != null,
                "get_financials not exposed by MCP server — skipping v2");
        return financialsAdapter;
    }

    /**
     * Builds the v2 sub-agent and grants it AbilityManager visibility to {@code get_financials} only
     * (reusing the host's global adapter — no re-registration, which would collide in the shared namespace).
     *
     * @param modelName LLM model name
     * @param financialsAdapter the host-registered get_financials adapter to gain visibility to
     * @return the configured sub-agent
     */
    private static ReActAgent newSubAgentWithFinancials(String modelName, McpToolAdapter financialsAdapter) {
        ReActAgent subAgent = new ReActAgent(AgentCard.builder().name("edpa-sub-v2-financials").build());
        subAgent.setLlm(buildModel(envKey(), envBase(), modelName, "sub-v2", 1000));
        bumpIterations(subAgent, 12);
        subAgent.getAbilityManager().add(financialsAdapter.getCard());
        LOG.log(Level.INFO, "{0}",
                "[subagent-v2] sub-agent gained visibility to get_financials "
                        + "(reusing host's global adapter; no re-registration)");
        return subAgent;
    }

    /**
     * Logs the v1 host-output diagnostics (length, preview, delegation/result counts).
     *
     * @param hostOutput the host's textual output
     * @param subInvokeCount delegation counter
     * @param subResultLen sub-agent result length
     */
    private static void logV1OutputDiag(String hostOutput, AtomicInteger subInvokeCount, AtomicInteger subResultLen) {
        LOG.log(Level.INFO, "[subagent-e2e] host output length: {0}", hostOutput.length());
        String preview = hostOutput.substring(0, Math.min(400, hostOutput.length()));
        LOG.log(Level.INFO, "[subagent-e2e] host output preview: {0}", preview);
        LOG.log(Level.INFO, "[subagent-e2e] sub-agent invoked (delegations): {0}", subInvokeCount.get());
        LOG.log(Level.INFO, "[subagent-e2e] sub-agent result length: {0}", subResultLen.get());
    }

    /**
     * Logs the v2 host-output diagnostics including the shared MCP dispatch counts.
     *
     * @param hostOutput the host's textual output
     * @param subInvokeCount delegation counter
     * @param mcp the counting MCP client
     */
    private static void logV2OutputDiag(String hostOutput, AtomicInteger subInvokeCount, CountingMcpClient mcp) {
        LOG.log(Level.INFO, "[subagent-v2] host output length: {0}", hostOutput.length());
        String preview = hostOutput.substring(0, Math.min(400, hostOutput.length()));
        LOG.log(Level.INFO, "[subagent-v2] host output preview: {0}", preview);
        LOG.log(Level.INFO, "[subagent-v2] sub-agent delegations: {0}", subInvokeCount.get());
        LOG.log(Level.INFO,
                "[subagent-v2] MCP get_financials dispatched (via shared global adapter): {0} times, tools={1}",
                new Object[] {mcp.callCount(), mcp.calledNames()});
    }

    /**
     * v1 HARD assertions: the host produced output, dispatched at least once to the sub-agent tool,
     * and the sub-agent (a real ReActAgent) produced substantive output — proving the AgentCard-as-tool
     * dispatch chain is live end-to-end.
     *
     * @param result the raw host invoke result
     * @param hostOutput extracted host textual output
     * @param subInvokeCount delegation counter
     * @param subResultLen sub-agent result length
     */
    private static void assertDispatchChain(Object result, String hostOutput, AtomicInteger subInvokeCount,
            AtomicInteger subResultLen) {
        assertThat(result).as("host loop must produce a non-null result").isNotNull();
        assertThat(hostOutput).as("host must produce non-empty output").isNotEmpty();
        assertThat(subInvokeCount.get()).as("host LLM must dispatch >=1 real call to the sub-agent tool "
                + "(proves the AgentCard-as-tool dispatch chain is live)").isGreaterThanOrEqualTo(1);
        assertThat(subResultLen.get()).as("sub-agent (a real ReActAgent) must produce substantive output — "
                + "a length >50 proves its LLM actually ran, not an empty/error degenerate").isGreaterThan(50);
    }

    /**
     * v2 HARD assertions: the host delegated to the sub-agent, the sub-agent's {@code get_financials}
     * dispatch reached the shared global MCP adapter, the dispatched tool was get_financials, and the
     * sub-agent produced substantive analysis from the real SEC data.
     *
     * @param result the raw host invoke result
     * @param hostOutput extracted host textual output
     * @param subInvokeCount delegation counter
     * @param subResultLen sub-agent result length
     * @param mcp the counting MCP client
     */
    private static void assertSharedMcpChain(Object result, String hostOutput, AtomicInteger subInvokeCount,
            AtomicInteger subResultLen, CountingMcpClient mcp) {
        assertThat(result).as("host loop must produce a non-null result").isNotNull();
        assertThat(hostOutput).as("host must produce non-empty output").isNotEmpty();
        assertThat(subInvokeCount.get()).as("host must delegate to the sub-agent").isGreaterThanOrEqualTo(1);
        assertThat(mcp.callCount())
                .as("sub-agent's get_financials dispatch must reach the shared global MCP adapter "
                        + "(proves the resourceMgr-global tool-pool pattern works end-to-end)")
                .isGreaterThanOrEqualTo(1);
        assertThat(mcp.calledNames()).as("the dispatched MCP tool must be get_financials").contains("get_financials");
        assertThat(subResultLen.get()).as("sub-agent must produce substantive analysis from the real SEC data")
                .isGreaterThan(50);
    }

    /**
     * Builds the counting SubAgentExecutor that runs the real sub-agent and captures dispatch proof
     * (invoke count, result length, and optionally a "rich analysis" overflow flag).
     *
     * @param subAgent the real ReActAgent to delegate into
     * @param subInvokeCount counter incremented on every delegation
     * @param subResultLen sink for the sub-agent's textual output length
     * @param subResultOverflow sink set to 1 when output exceeds 200 chars (v1 only; pass {@code null} for v2)
     * @return the configured executor
     */
    private static SubAgentExecutor newSubAgentExecutor(ReActAgent subAgent, AtomicInteger subInvokeCount,
            AtomicInteger subResultLen, AtomicInteger subResultOverflow) {
        return (userInput, subGoal) -> {
            subInvokeCount.incrementAndGet();
            try {
                Object res = subAgent.invoke(subGoal, null);
                String text = extractOutput(res);
                subResultLen.set(text.length());
                if (subResultOverflow != null && text.length() > 200) {
                    subResultOverflow.set(1);
                }
                return text;
            } catch (Exception | Error e) {
                // CodeCheck G.ERR.02: subAgent.invoke declares throws Exception, but the
                // SubAgentExecutor functional interface allows no checked throws. We catch the
                // full throwable spectrum explicitly (Exception | Error, not a bare catch-all)
                // because LLM/tooling stack failures span IO (checked) and runtime/error paths:
                // checked failures are wrapped in a named RuntimeException (cause preserved),
                // unchecked failures and Errors propagate unchanged via the rethrows below.
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                if (e instanceof Error error) {
                    throw error;
                }
                throw new SubAgentInvocationException("sub-agent invoke failed: " + e.getMessage(), e);
            }
        };
    }

    /**
     * Starts the SEC EDGAR stdio MCP server behind a counting wrapper, or aborts the test via a
     * JUnit assumption (with resource cleanup) when the server is unavailable.
     *
     * @return the started counting MCP client
     */
    private static CountingMcpClient startCountingMcpOrSkip() {
        StdioMcpClient rawMcp = new StdioMcpClient(List.of("python3", "-m", "sec_edgar_mcp.server"),
                Map.of("SEC_EDGAR_USER_AGENT",
                        System.getenv().getOrDefault("SEC_EDGAR_USER_AGENT", "EDPA Research e2e test@example.com")));
        CountingMcpClient mcp = new CountingMcpClient(rawMcp);
        try {
            rawMcp.start();
        } catch (Exception | Error e) {
            // CodeCheck: e2e test 容错 catch-all — stdio MCP start can fail for IO or non-IO reasons
            // (e.g. missing python module surfacing as IllegalStateException, or Error on classpath
            // issues); we skip the test rather than hard-failing the suite, matching env-gated intent.
            rawMcp.close();
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "SEC EDGAR MCP unavailable (" + e.getMessage() + ") — pip install sec-edgar-mcp");
            return mcp;
        }
        return mcp;
    }

    /**
     * Emits the resourceMgr-global-namespace probe log (v1) documenting the documented-not-asserted
     * isolation finding that Runner.resourceMgr() is a process-global singleton.
     *
     * @param delegateTool the tool object resolved from the global resourceMgr after registration
     */
    private static void logResourceMgrProbe(Object delegateTool) {
        String presence = delegateTool != null ? "<present, global namespace>" : "<null>";
        LOG.log(Level.INFO,
                "[subagent-e2e] resourceMgr.getTool(''delegate_analysis'') after register: {0}", presence);
        LOG.log(Level.INFO, "{0}",
                "[subagent-e2e] resourceMgr isolation note: Runner.resourceMgr() is a "
                        + "process-global singleton; sub-agent reuses host tools via visibility, no isolation.");
    }

    /**
     * Builds a ToolCallingEnforcingModel against the OpenAI-compatible OpenJiuwen endpoint with a
     * role-namespaced client id and a per-role maxTokens cap.
     *
     * @param key API key
     * @param base API base URL
     * @param modelName LLM model name
     * @param role role tag used to namespace the model client id
     * @param maxTokens maxTokens cap for the request
     * @return the configured enforcing model
     */
    private static ToolCallingEnforcingModel buildModel(String key, String base, String modelName, String role,
            int maxTokens) {
        var cliCfg = ModelClientConfig.builder().clientId("edpa-subagent-" + role + "-" + System.nanoTime())
                .clientProvider("OpenAI").apiKey(key).apiBase(base).verifySsl(false).timeout(120000).build();
        var reqCfg = ModelRequestConfig.builder().modelName(modelName).temperature(0.3).topP(0.9).maxTokens(maxTokens)
                .build();
        return new ToolCallingEnforcingModel(cliCfg, reqCfg);
    }

    /**
     * Bumps the ReActAgent's max-iterations cap if its config is a ReActAgentConfig (no-op otherwise).
     *
     * @param agent the agent whose iteration cap to bump
     * @param n the new max-iterations value
     */
    private static void bumpIterations(ReActAgent agent, int n) {
        Object cfg = agent.getConfig();
        if (cfg instanceof com.openjiuwen.core.singleagent.agents.ReActAgentConfig reactCfg) {
            reactCfg.configureMaxIterations(n);
        }
    }

    /**
     * Extracts the textual answer from a ReActAgent result.
     *
     * @param result the raw agent result (Map with "output" key, or bare string); may be {@code null}
     * @return the extracted text, or empty string when {@code null}
     */
    private static String extractOutput(Object result) {
        if (result == null) {
            return "";
        }
        if (result instanceof Map<?, ?> m) {
            Object out = m.get("output");
            return out == null ? String.valueOf(result) : String.valueOf(out);
        }
        return String.valueOf(result);
    }

    /**
     * Named unchecked exception used to wrap checked failures from {@code subAgent.invoke} inside the
     * {@link SubAgentExecutor} lambda (whose functional interface declares no checked throws), so the
     * original cause is preserved without resorting to a raw {@link RuntimeException}.
     *
     * @since 2026-07
     */
    static final class SubAgentInvocationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /**
         * Constructs a sub-agent invocation failure wrapping the original checked cause.
         *
         * @param message detail message describing the sub-agent invocation failure
         * @param cause the underlying checked exception thrown by {@code subAgent.invoke}
         */
        SubAgentInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Delegating McpClient that counts tools/call dispatches — proves the sub-agent drove the shared MCP.
     *
     * @since 2026-07
     */
    static final class CountingMcpClient implements McpClient {
        private final McpClient delegate;
        private final AtomicInteger callCount = new AtomicInteger();
        private final Set<String> called = java.util.concurrent.ConcurrentHashMap.newKeySet();

        /**
         * Wraps an MCP client with counting instrumentation for dispatch proof.
         *
         * @param delegate the underlying MCP client to delegate every call to
         */
        CountingMcpClient(McpClient delegate) {
            this.delegate = delegate;
        }

        /**
         * Lists tools exposed by the underlying delegated MCP client.
         *
         * @return the delegated tool list
         * @throws Exception if the underlying MCP call fails
         */
        @Override
        public List<McpTool> listTools() throws Exception {
            return delegate.listTools();
        }

        /**
         * Counts the dispatch and records the tool name before delegating the call.
         *
         * @param name the tool name to call
         * @param arguments the tool arguments
         * @return the delegated call result text
         * @throws Exception if the underlying MCP call fails
         */
        @Override
        public String callTool(String name, Map<String, Object> arguments) throws Exception {
            callCount.incrementAndGet();
            called.add(name);
            return delegate.callTool(name, arguments);
        }

        /**
         * Closes the underlying delegated MCP client.
         */
        @Override
        public void close() {
            delegate.close();
        }

        /**
         * Returns how many tool dispatches the wrapper has observed so far.
         *
         * @return the number of tool dispatches observed so far
         */
        int callCount() {
            return callCount.get();
        }

        /**
         * Returns a snapshot of the distinct tool names that were dispatched.
         *
         * @return a snapshot of the distinct tool names that were dispatched
         */
        Set<String> calledNames() {
            return new HashSet<>(called);
        }
    }
}
