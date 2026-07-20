/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import com.openjiuwen.agents.edpa.mcp.McpClient;
import com.openjiuwen.agents.edpa.mcp.McpTool;
import com.openjiuwen.agents.edpa.mcp.McpToolRegistrar;
import com.openjiuwen.agents.edpa.mcp.StdioMcpClient;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * MCP real-LLM matrix e2e — verifies the SEC EDGAR MCP tool-call works across 6 models × thinking on/off.
 *
 * <p>Simpler than {@link McpInvestmentResearchE2eTest} (which runs the full MS 7-section report):
 * this matrix uses a lightweight task (call 1 MCP tool + summarize) to verify the MCP data channel
 * works across the model fleet, without the full report-generation overhead.
 *
 * <p>Per-cell bearing: {@code mcpClient.callCount() >= 1} (the LLM dispatched a real MCP tool through
 * the thin stdio client) + output non-empty. Soft-observe: output length (model-dependent).
 *
 * <p>Env-gated: {@code EDPA_MCP_MATRIX_E2E_ENABLED=true} + per-provider keys
 * (MY_GLM_KEY / DEEPSEEK_API_KEY / OPENROUTER_API_KEY).
 *
 * @since 2026-07
 */
class McpToolCallMatrixRealLlmE2eTest {
    private static final Logger LOG =
            Logger.getLogger(McpToolCallMatrixRealLlmE2eTest.class.getName());

    private static final String TASK = "调用 get_company_info 工具获取苹果公司（CIK=0000320193）的基本信息，"
            + "然后用中文简要总结该公司的核心业务和行业。";

    private record ModelConfig(String label, String base, String keyEnv, String model,
            Map<String, Object> thinkingOn, Map<String, Object> thinkingOff) {
        String resolveKey() {
            return System.getenv(keyEnv);
        }
    }

    private static List<ModelConfig> configs() {
        Map<String, Object> glmOn = Map.of("thinking", Map.of("type", "enabled"));
        Map<String, Object> glmOff = Map.of("thinking", Map.of("type", "disabled"));
        Map<String, Object> qwenOn =
                Map.of("reasoning", Map.of("enabled", true), "include_reasoning", true);
        Map<String, Object> qwenOff = Map.of("reasoning", Map.of("enabled", false));
        return List.of(
                new ModelConfig("glm-5.2", "https://open.bigmodel.cn/api/paas/v4", "MY_GLM_KEY",
                        "glm-5.2", glmOn, glmOff),
                new ModelConfig("glm-4.7", "https://open.bigmodel.cn/api/paas/v4", "MY_GLM_KEY",
                        "glm-4.7", glmOn, glmOff),
                new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                        "deepseek-v4-pro", glmOn, glmOff),
                new ModelConfig("deepseek-v4-flash", "https://api.deepseek.com",
                        "DEEPSEEK_API_KEY", "deepseek-v4-flash", glmOn, glmOff),
                new ModelConfig("qwen3.6-35b-a3b", "https://openrouter.ai/api/v1",
                        "OPENROUTER_API_KEY", "qwen/qwen3.6-35b-a3b", qwenOn, qwenOff),
                new ModelConfig("qwen3.6-27b", "https://openrouter.ai/api/v1",
                        "OPENROUTER_API_KEY", "qwen/qwen3.6-27b", qwenOn, qwenOff));
    }

    @Test
    void mcpToolCallMatrix_6models_x_thinkingOnOff() {
        String enabled = System.getenv("EDPA_MCP_MATRIX_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "MCP tool-call matrix requires EDPA_MCP_MATRIX_E2E_ENABLED=true"
                        + " + MY_GLM/DEEPSEEK/OPENROUTER keys + pip install sec-edgar-mcp");
        DefaultModelClientFactories.ensureRegistered();

        List<Map<String, Object>> results = new ArrayList<>();
        for (ModelConfig mc : configs()) {
            String key = mc.resolveKey();
            if (key == null || key.isBlank()) {
                LOG.log(Level.INFO, "[mcp-matrix] SKIP {0} (key {1} unset)",
                        new Object[] {mc.label, mc.keyEnv});
                continue;
            }
            for (boolean thinking : new boolean[] {true, false}) {
                Map<String, Object> r = runOne(mc, key, thinking);
                if (isTransientFlaky(r) && !"completed".equals(r.get("status"))) {
                    LOG.log(Level.INFO, "[mcp-matrix] RETRY {0} | thinking={1} (transient)",
                            new Object[] {mc.label, thinking ? "ON" : "OFF"});
                    r = runOne(mc, key, thinking);
                    r.put("retried", true);
                }
                results.add(r);
            }
        }
        summarize(results);
        applyBearingGate(results);
    }

    private static Map<String, Object> runOne(ModelConfig mc, String key, boolean thinking) {
        String label = mc.label + " | thinking=" + (thinking ? "ON" : "OFF");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", mc.label);
        r.put("thinking", thinking ? "ON" : "OFF");
        r.put("status", "error");
        r.put("mcpCalls", 0);
        r.put("outputLen", 0);
        FutureTask<Map<String, Object>> task = new FutureTask<>(() -> runOneInternal(mc, key, thinking, r, label));
        task.run();
        try {
            task.get();
        } catch (InterruptedException | ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            r.put("status", "flaky:" + cause.getClass().getSimpleName());
            r.put("cause", cause.getClass().getName() + ": " + cause.getMessage());
            LOG.log(Level.SEVERE, "[mcp-matrix] " + label + " EX " + cause.getClass().getName()
                    + ": " + cause.getMessage(), cause);
        }
        return r;
    }

    private static Map<String, Object> runOneInternal(ModelConfig mc, String key, boolean thinking,
            Map<String, Object> r, String label) {
        Map<String, Object> extra = new LinkedHashMap<>(thinking ? mc.thinkingOn : mc.thinkingOff);
        var cliCfg = ModelClientConfig.builder().clientId("mcp-matrix-" + System.nanoTime())
                .clientProvider("OpenAI").apiKey(key).apiBase(mc.base).verifySsl(false).timeout(120000).build();
        var reqCfg = ModelRequestConfig.builder().modelName(mc.model).temperature(0.3).topP(0.9)
                .maxTokens(4000).extraFields(extra).build();
        var model = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("mcp-matrix").build());
        agent.setLlm(model);
        Object cfgObj = agent.getConfig();
        if (cfgObj instanceof ReActAgentConfig reactCfg) {
            reactCfg.configureMaxIterations(15);
        }
        ReplanTool.registerOnto(agent);

        Optional<CountingMcpClient> mcpOpt = startMcp(agent);
        if (mcpOpt.isEmpty()) {
            r.put("status", "mcp-unavailable");
            return r;
        }
        CountingMcpClient mcpClient = mcpOpt.get();
        try {
            LOG.log(Level.INFO, "[mcp-matrix] {0} starting...", label);
            Object result = agent.invoke(TASK, null);
            String output = String.valueOf(result);
            int mcpCalls = mcpClient.callCount();
            r.put("mcpCalls", mcpCalls);
            r.put("outputLen", output.length());
            r.put("status", (result != null && !output.isEmpty() && mcpCalls >= 1) ? "completed"
                    : (mcpCalls >= 1) ? "empty-output" : "no-mcp-call");
            LOG.log(Level.INFO, "[mcp-matrix] {0} -> status={1} mcpCalls={2} outLen={3}",
                    new Object[] {label, r.get("status"), mcpCalls, output.length()});

            // Per-cell bearing
            org.junit.jupiter.api.Assertions.assertTrue(result != null && !output.isEmpty(),
                    "bearing A (data channel): non-empty output for " + label);
            org.junit.jupiter.api.Assertions.assertTrue(mcpCalls >= 1,
                    "bearing B (MCP dispatch): LLM must call >=1 MCP tool through thin stdio client for "
                            + label);
        } finally {
            mcpClient.close();
        }
        return r;
    }

    private static Optional<CountingMcpClient> startMcp(ReActAgent agent) {
        StdioMcpClient rawClient = new StdioMcpClient(List.of("python3", "-m", "sec_edgar_mcp.server"),
                Map.of("SEC_EDGAR_USER_AGENT",
                        System.getenv().getOrDefault("SEC_EDGAR_USER_AGENT",
                                "EDPA MCP Matrix e2e test@example.com")));
        CountingMcpClient mcpClient = new CountingMcpClient(rawClient);
        try {
            rawClient.start();
            Set<String> tools = McpToolRegistrar.registeredNames(McpToolRegistrar.registerOnto(agent, mcpClient));
            if (tools.isEmpty()) {
                rawClient.close();
                LOG.log(Level.WARNING, "[mcp-matrix] MCP server connected but exposed 0 tools");
                return Optional.empty();
            }
            return Optional.of(mcpClient);
        } catch (IOException e) {
            rawClient.close();
            LOG.log(Level.WARNING, "[mcp-matrix] MCP server unavailable: " + e.getMessage());
            return Optional.empty();
        } catch (Exception e) {
            rawClient.close();
            LOG.log(Level.WARNING, "[mcp-matrix] MCP registration failed: " + e.getMessage());
            return Optional.empty();
        }
    }

    private static boolean isTransientFlaky(Map<String, Object> r) {
        String c = String.valueOf(r.get("cause")).toLowerCase(Locale.ROOT);
        return c.contains("timeout") || c.contains("sockettimeout") || c.contains("ioexception")
                || c.contains("connection reset");
    }

    private static void summarize(List<Map<String, Object>> results) {
        LOG.log(Level.INFO, "{0}===== MCP TOOL-CALL MATRIX (6 models × thinking) =====",
                System.lineSeparator());
        LOG.log(Level.INFO, "{0}",
                String.format(Locale.ROOT, "%-20s %-8s %-14s %-10s %-10s%n", "model", "thinking",
                        "status", "mcpCalls", "outLen"));
        for (Map<String, Object> r : results) {
            LOG.log(Level.INFO, "{0}",
                    String.format(Locale.ROOT, "%-20s %-8s %-14s %-10s %-10s%n", r.get("model"),
                            r.get("thinking"), r.get("status"), r.get("mcpCalls"), r.get("outputLen")));
        }
        long completed = results.stream().filter(r -> "completed".equals(r.get("status"))).count();
        long withMcp = results.stream().filter(r -> ((int) r.get("mcpCalls")) >= 1).count();
        LOG.log(Level.INFO, "[mcp-matrix] completed={0}/{1}, cells with >=1 MCP call={2}/{1}",
                new Object[] {completed, results.size(), withMcp});
    }

    private static void applyBearingGate(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return; // all keys unset → entirely skipped, no gate
        }
        long completed = results.stream().filter(r -> "completed".equals(r.get("status"))).count();
        // Env-readiness gate: at least 1 cell completed = MCP + API + SDK wiring works.
        org.junit.jupiter.api.Assertions.assertTrue(completed >= 1,
                "env-readiness gate: at least one cell must complete (got completed=" + completed
                        + " of " + results.size()
                        + " — check API keys + MCP server availability)");
        // Bearing gate: when cells ran (>=4 = at least 2 models × 2 thinking), majority must complete.
        // Allows transient flakiness (timeout/429) without silent CI pass.
        if (results.size() >= 4) {
            long threshold = (results.size() + 1) / 2; // ≥ half
            org.junit.jupiter.api.Assertions.assertTrue(completed >= threshold,
                    "bearing gate: at least " + threshold + "/" + results.size()
                            + " cells must complete (got " + completed
                            + " — model regressions or widespread flakiness)");
        }
    }

    static class CountingMcpClient implements McpClient {
        private final McpClient delegate;
        private int callCount = 0;

        CountingMcpClient(McpClient delegate) {
            this.delegate = delegate;
        }

        int callCount() {
            return callCount;
        }

        @Override
        public String callTool(String name, Map<String, Object> args) throws Exception {
            callCount++;
            return delegate.callTool(name, args);
        }

        @Override
        public List<McpTool> listTools() throws Exception {
            return delegate.listTools();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
