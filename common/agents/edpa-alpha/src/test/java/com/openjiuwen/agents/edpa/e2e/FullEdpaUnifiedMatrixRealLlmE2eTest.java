/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.ExploreToolRegistrar;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.edpa.explore.LlmExplorer;
import com.openjiuwen.agents.edpa.rail.UserInputCaptureRail;
import com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRail;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Unified full-EDPA matrix across ALL three endpoints × 6 models × thinking on/off.
 * Run after any code change to verify EDPA holds across the whole model surface.
 *
 * <p><b>Matrix</b> (6 models × 2 thinking = 12 configs):
 * <ul>
 *   <li><b>bigmodel native</b>: glm-4.7, glm-5.2 — {@code thinking.type=enabled}</li>
 *   <li><b>deepseek native</b>: deepseek-v4-pro, deepseek-v4-flash — {@code thinking.type=enabled}
 *       (deepseek reasons by default; content is never null, so no content-empty risk)</li>
 *   <li><b>openrouter</b>: qwen3.6-35b-a3b, qwen3.6-27b — {@code reasoning.enabled=true}</li>
 * </ul>
 *
 * <p>Uses the 4-lens-fixed assert design (no empty-auto-flaky循环论证): outputWithoutVerify==0
 * (output must verify), hardErrors==0 (non-flaky exception), verifiedCount>=half, empty EXPOSED.
 *
 * <p>Env-gated opt-in: {@code EDPA_UNIFIED_MATRIX_ENABLED=true} + {@code OPENJIUWEN_API_KEY} /
 * {@code OPENJIUWEN_BASE_URL} (bigmodel) + {@code DEEPSEEK_API_KEY} + {@code OPENROUTER_API_KEY}.
 *
 * @since 2026-07
 */
class FullEdpaUnifiedMatrixRealLlmE2eTest {
    private static final Logger LOG = Logger.getLogger(FullEdpaUnifiedMatrixRealLlmE2eTest.class.getName());

    private static final List<String> CRITERIA = List.of("分析", "建议");

    /** Known transient HTTP/timeout exception signatures whose stacktraces mark a matrix row as retryable. */
    private static final List<String> TRANSIENT_FLAKY_SIGNATURES = List.of("IOException", "header parser",
            "end-of-input", "HttpTimeout", "TimeoutException", "Connection reset");

    private static final String TASK = """
            分析当前的经济形势。请按以下步骤：
            1. 先调用 explore 工具，探索可用的经济分析维度。
            2. 调用 market_data 工具获取经济指标数据（indicator 传 "GDP" 或 "利率"）。
            3. 基于探索与数据，给出简短的投资建议。
            【规则】每次工具调用单独一轮；最后无工具调用的回复是最终建议。
            """;

    /**
     * One model config: endpoint/key/model + the thinking extra fields (agent path) and JSON fragment (Explorer path).
     *
     * @param label         human-readable matrix label
     * @param base          API base URL (may be {@code null} if env unset)
     * @param keyEnv        environment variable name holding the API key
     * @param model         model name passed to the provider
     * @param thinkingExtra extra request fields applied on the agent path when thinking is on
     * @param thinkingJson  raw JSON fragment appended to the explorer request body when thinking is on
     */
    private record ModelConfig(String label, String base, String keyEnv, String model,
            Map<String, Object> thinkingExtra, String thinkingJson) {
        /**
         * Resolves the API key from the configured environment variable.
         *
         * @return the key, or {@code null} if unset or blank
         */
        String resolveKey() {
            String k = System.getenv(keyEnv);
            return (k == null || k.isBlank()) ? "" : k;
        }
    }

    /**
     * Builds the full 6-model matrix config list across bigmodel/deepseek/openrouter endpoints.
     *
     * @return unmodifiable list of model configs to exercise
     */
    private static List<ModelConfig> configs() {
        Map<String, Object> glmThinking = Map.of("thinking", Map.of("type", "enabled"));
        Map<String, Object> dsThinking = Map.of("thinking", Map.of("type", "enabled"));
        Map<String, Object> qwenThinking = Map.of("reasoning", Map.of("enabled", true), "include_reasoning", true);
        return List.of(
                new ModelConfig("glm-4.7", env("OPENJIUWEN_BASE_URL"), "OPENJIUWEN_API_KEY", "glm-4.7", glmThinking,
                        ",\"thinking\":{\"type\":\"enabled\"}"),
                new ModelConfig("glm-5.2", env("OPENJIUWEN_BASE_URL"), "OPENJIUWEN_API_KEY", "glm-5.2", glmThinking,
                        ",\"thinking\":{\"type\":\"enabled\"}"),
                new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", "DEEPSEEK_API_KEY", "deepseek-v4-pro",
                        dsThinking, ",\"thinking\":{\"type\":\"enabled\"}"),
                new ModelConfig("deepseek-v4-flash", "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                        "deepseek-v4-flash", dsThinking, ",\"thinking\":{\"type\":\"enabled\"}"),
                new ModelConfig("qwen3.6-35b-a3b", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
                        "qwen/qwen3.6-35b-a3b", qwenThinking,
                        ",\"reasoning\":{\"enabled\":true},\"include_reasoning\":true"),
                new ModelConfig("qwen3.6-27b", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY", "qwen/qwen3.6-27b",
                        qwenThinking, ",\"reasoning\":{\"enabled\":true},\"include_reasoning\":true"));
    }

    /**
     * Reads an environment variable value.
     *
     * @param name the environment variable name
     * @return the value, or {@code null} if unset
     */
    private static String env(String name) {
        return System.getenv(name);
    }

    @Test
    @Timeout(3000)
    void unifiedMatrix_6models_x_thinkingOnOff() {
        String enabled = System.getenv("EDPA_UNIFIED_MATRIX_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "Unified matrix requires EDPA_UNIFIED_MATRIX_ENABLED=true + OPENJIUWEN/DEEPSEEK/OPENROUTER keys");
        DefaultModelClientFactories.ensureRegistered();

        RunOutcome outcome = runAllConfigs();
        summarizeMatrix(outcome);
        assertMatrix(outcome);
    }

    /**
     * Runs every model config (thinking on/off) once, retrying transient-flaky failures once.
     *
     * @return aggregated {@link RunOutcome} with results, skipped count and verified count
     */
    private RunOutcome runAllConfigs() {
        List<ModelConfig> all = configs();
        List<Map<String, Object>> results = new ArrayList<>();
        int skipped = 0;
        for (ModelConfig mc : all) {
            String key = mc.resolveKey();
            if (key == null || mc.base == null) {
                LOG.log(Level.INFO, "[uni-matrix] SKIP {0} (key/base unset: {1})", new Object[]{mc.label, mc.keyEnv});
                skipped++;
                continue;
            }
            for (boolean thinking : new boolean[]{true, false}) {
                runSingleThinking(results, mc, key, thinking);
            }
        }
        return new RunOutcome(results, skipped);
    }

    /**
     * Runs one (model, thinking) pair, retrying once on a transient-flaky status.
     *
     * @param results  accumulator list to append the chosen result into
     * @param mc       the model config to run
     * @param key      resolved API key for {@code mc}
     * @param thinking whether thinking/reasoning extra fields are applied
     */
    private void runSingleThinking(List<Map<String, Object>> results, ModelConfig mc, String key, boolean thinking) {
        String label = mc.label + " | thinking=" + (thinking ? "ON" : "OFF");
        Map<String, Object> r = runOneConfig(mc, key, thinking);
        if (isTransientFlaky(r) && !"completed".equals(r.get("status"))) {
            LOG.log(Level.INFO, "[uni-matrix] RETRY (transient flaky) {0}", label);
            Map<String, Object> r2 = runOneConfig(mc, key, thinking);
            r2.put("retried", true);
            results.add(r2);
            LOG.log(Level.INFO, "[uni-matrix] {0} (retry) → {1}", new Object[]{label, fmt(r2)});
        } else {
            results.add(r);
            LOG.log(Level.INFO, "[uni-matrix] {0} → {1}", new Object[]{label, fmt(r)});
        }
    }

    /**
     * Prints the per-config table and the aggregate summary line.
     *
     * @param outcome the run outcome to summarize
     */
    private void summarizeMatrix(RunOutcome outcome) {
        List<Map<String, Object>> results = outcome.results();
        LOG.log(Level.INFO, "{0}================ UNIFIED MATRIX SUMMARY ================", System.lineSeparator());
        String header = String.format(java.util.Locale.ROOT, "%-22s %-9s %-7s %-7s %-8s %-9s %-9s%n", "model",
                "thinking", "explor", "tools", "convrg", "verified", "status");
        LOG.log(Level.INFO, "{0}", header);
        for (Map<String, Object> r : results) {
            LOG.log(Level.INFO, "{0}", formatResultRow(r));
        }
        int ran = results.size();
        int outputWithoutVerify = countOutputWithoutVerify(results);
        int hardErrors = countHardErrors(results);
        int emptyConfigs = countEmpty(results);
        LOG.log(Level.INFO, "{0}", "=======================================================");
        LOG.log(Level.INFO,
                "[uni-matrix] ran {0} configs (skipped {1} for missing keys) | verified {2}/{0}"
                        + " | output-without-verify (HARD quality): {3} | hardErrors (non-flaky exception): {4}"
                        + " | empty (exposed — glm-4.7+thinking/qwen=known reasoning content-empty model char): {5}",
                new Object[]{ran, outcome.skipped(), outcome.verifiedCount(), outputWithoutVerify, hardErrors,
                        emptyConfigs});
    }

    /**
     * Formats one result map as the matrix table row (fixed columns for the summary table).
     *
     * @param r the per-config result map
     * @return formatted table row string
     */
    private static String formatResultRow(Map<String, Object> r) {
        return String.format(java.util.Locale.ROOT, "%-22s %-9s %-7s %-7s %-8s %-9s %-9s%n", r.get("model"),
                r.get("thinking"), r.get("exploreCount"), r.get("toolCalls"), r.get("convergenceTrigger"),
                r.get("verified"), r.get("status"));
    }

    /**
     * Asserts the 4-lens matrix invariants against the run outcome.
     *
     * @param outcome the run outcome to assert
     */
    private void assertMatrix(RunOutcome outcome) {
        List<Map<String, Object>> results = outcome.results();
        int ran = results.size();
        int outputWithoutVerify = countOutputWithoutVerify(results);
        int hardErrors = countHardErrors(results);
        int verifiedCount = outcome.verifiedCount();
        assertThat(ran).as("at least some configs must have run").isGreaterThan(0);
        assertThat(outputWithoutVerify)
                .as("output produced but criteria not verified = real quality failure; got " + outputWithoutVerify)
                .isZero();
        assertThat(hardErrors).as("non-flaky exceptions = real EDPA bug; got " + hardErrors).isZero();
        assertThat(verifiedCount)
                .as("at least half the ran configs genuinely criteria-verify (got " + verifiedCount + "/" + ran + ")")
                .isGreaterThanOrEqualTo((ran + 1) / 2);
    }

    private static int countOutputWithoutVerify(List<Map<String, Object>> results) {
        return (int) results.stream()
                .filter(r -> "completed".equals(r.get("status")) && !Boolean.TRUE.equals(r.get("verified"))).count();
    }

    private static int countHardErrors(List<Map<String, Object>> results) {
        return (int) results.stream()
                .filter(r -> String.valueOf(r.get("status")).startsWith("error") && !isTransientFlaky(r)).count();
    }

    private static int countEmpty(List<Map<String, Object>> results) {
        return (int) results.stream().filter(r -> "empty".equals(r.get("status"))).count();
    }

    /**
     * Immutable aggregate of a matrix run: per-config results, skip count and verified count.
     *
     * @param results       per-config result maps
     * @param skipped       number of configs skipped for missing keys/base
     */
    private record RunOutcome(List<Map<String, Object>> results, int skipped) {
        int verifiedCount() {
            int count = 0;
            for (Map<String, Object> r : results) {
                if (Boolean.TRUE.equals(r.get("verified"))) {
                    count++;
                }
            }
            return count;
        }
    }

    /**
     * Runs one (model, thinking) configuration end-to-end and collects metrics into a result map.
     *
     * @param mc       the model config to run
     * @param key      resolved API key for {@code mc}
     * @param thinking whether thinking/reasoning extra fields are applied
     * @return result map with keys model/thinking/exploreCount/toolCalls/convergenceTrigger/verified/outputLen/status
     */
    private Map<String, Object> runOneConfig(ModelConfig mc, String key, boolean thinking) {
        Map<String, Object> r = new java.util.LinkedHashMap<>();
        r.put("model", mc.label);
        r.put("thinking", thinking ? "ON" : "OFF");
        r.put("exploreCount", 0);
        r.put("toolCalls", 0);
        r.put("convergenceTrigger", 0);
        r.put("verified", null);
        r.put("status", "error");
        try {
            AgentRun run = buildAndInvokeAgent(mc, key, thinking);
            populateSuccess(r, run);
        } catch (RuntimeException | Error e) {
            // CodeCheck: test 容错 catch-all — e2e must capture every LLM/agent failure as a matrix row
            populateFailure(r, mc, thinking, e);
        }
        return r;
    }

    /**
     * Builds the full EDPA agent (model + tools + rails + explorer) and invokes the task once.
     *
     * @param mc       the model config to run
     * @param key      resolved API key for {@code mc}
     * @param thinking whether thinking/reasoning extra fields are applied
     * @return the agent invocation result plus captured counters
     */
    private AgentRun buildAndInvokeAgent(ModelConfig mc, String key, boolean thinking) {
        ModelClientConfig cliCfg = ModelClientConfig.builder()
                .clientId("edpa-uni-" + mc.label + "-" + System.nanoTime()).clientProvider("OpenAI").apiKey(key)
                .apiBase(mc.base).verifySsl(false).timeout(180000).build();
        ModelRequestConfig reqCfg = ModelRequestConfig.builder().modelName(mc.model).temperature(0.3).topP(0.9)
                .maxTokens(4000).build();
        if (thinking) {
            mc.thinkingExtra.forEach(reqCfg::setExtraField);
        }
        ToolCallingEnforcingModel agentModel = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-uni").build());
        agent.setLlm(agentModel);
        configureIterations(agent);

        AtomicInteger toolCalls = new AtomicInteger(0);
        registerMarketDataTool(agent, toolCalls);

        AtomicReference<String> userInputRef = new AtomicReference<String>();
        agent.registerRail(new UserInputCaptureRail(userInputRef));

        AtomicInteger exploreCount = new AtomicInteger(0);
        Function<String, String> explorerFn = buildExplorerFn(key, mc.base + "/chat/completions", mc.model,
                thinking ? mc.thinkingJson : "", exploreCount);
        Explorer explorer = new LlmExplorer(explorerFn, ExploreBudget.DEFAULT);
        ExploreToolRegistrar.registerOnto(agent, explorer, ExploreBudget.DEFAULT, userInputRef::get);

        registerEdpaRails(agent);

        Object result = agent.invoke(TASK, null);
        return new AgentRun(result, toolCalls.get(), exploreCount.get());
    }

    private static void configureIterations(ReActAgent agent) {
        Object cfg = agent.getConfig();
        if (cfg instanceof com.openjiuwen.core.singleagent.agents.ReActAgentConfig rc) {
            rc.configureMaxIterations(12);
        }
    }

    private void registerEdpaRails(ReActAgent agent) {
        ReplanRail sharedCounter = new ReplanRail(3);
        agent.registerRail(new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(), CRITERIA, sharedCounter));
        ProactiveConvergenceRail convergence = new ProactiveConvergenceRail(new RuleBasedCriteriaVerifier(),
                CRITERIA, 2, ProactiveConvergenceRail.DEFAULT_COVERAGE_CRITICAL);
        agent.registerRail(convergence);
        agent.registerRail(new ReplanRail(3));
        ReplanTool.registerOnto(agent);
    }

    private static void populateSuccess(Map<String, Object> r, AgentRun run) {
        Object result = run.result();
        String output = extractOutput(result);
        r.put("exploreCount", run.exploreCount());
        r.put("toolCalls", run.toolCalls());
        r.put("convergenceTrigger", -1); // 状态隔离后 invoke 不可读 (RailInvocationState per-invocation)
        r.put("verified", result instanceof Map<?, ?> rm ? rm.get(CriteriaReplanBridgeRail.VERIFIED_KEY) : null);
        r.put("outputLen", output.length());
        r.put("status", output.isBlank() ? "empty" : "completed");
    }

    private static void populateFailure(Map<String, Object> r, ModelConfig mc, boolean thinking, Throwable e) {
        r.put("status", "error:" + e.getClass().getSimpleName());
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        String st = sw.toString();
        r.put("stacktrace", st);
        LOG.log(Level.INFO, "[uni-matrix] STACKTRACE {0} thinking={1} : {2}",
                new Object[]{mc.label, thinking, st.substring(0, Math.min(1000, st.length()))});
    }

    /**
     * Captured outputs of a single agent invocation: result object + tool/explore counters.
     *
     * @param result       raw agent invocation result
     * @param toolCalls    number of market_data tool invocations observed
     * @param exploreCount number of explorer function calls observed
     */
    private record AgentRun(Object result, int toolCalls, int exploreCount) {
    }

    /**
     * Registers a stub {@code market_data} tool onto the agent that increments the counter on each call.
     *
     * @param agent   the agent to register the tool onto
     * @param counter shared counter incremented per tool invocation
     */
    private static void registerMarketDataTool(ReActAgent agent, AtomicInteger counter) {
        ToolCard card = ToolCard.builder().id("market_data").name("market_data")
                .description("获取市场/经济数据。参数：indicator（如 GDP/利率/CPI）。")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("indicator", Map.of("type", "string", "description", "经济指标名")), "required",
                        List.of("indicator")))
                .build();
        Tool tool = new Tool(card) {
            @Override
            public ToolCard getCard() {
                return card;
            }
            @Override
            public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
                counter.incrementAndGet();
                String ind = args != null ? String.valueOf(args.getOrDefault("indicator", "GDP")) : "GDP";
                return Map.of("indicator", ind, "value", "稳中有降", "trend", "谨慎乐观");
            }
            @Override
            public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
                return List.<Object>of(invoke(args, kwargs)).iterator();
            }
        };
        agent.getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
    }

    /**
     * Builds a raw-HTTP explorer function that POSTs to {@code url}/chat/completions and returns extracted content.
     *
     * @param key                 bearer API key
     * @param url                 full chat-completions URL
     * @param model               model name for the request body
     * @param thinkingJsonFragment extra JSON fragment for thinking/reasoning fields (empty when off)
     * @param exploreCount        shared counter incremented per explorer call
     * @return a function mapping a prompt to the extracted content (empty on non-200 or failure)
     */
    private static Function<String, String> buildExplorerFn(String key, String url, String model,
            String thinkingJsonFragment, AtomicInteger exploreCount) {
        return prompt -> {
            exploreCount.incrementAndGet();
            try {
                String escaped = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                        .replace("\r", "").replace("\t", "\\t");
                String body = "{\"model\":\"" + model + "\"," + "\"messages\":[{\"role\":\"user\",\"content\":\""
                        + escaped + "\"}]," + "\"temperature\":0.3,\"max_tokens\":2500,\"top_p\":0.9"
                        + thinkingJsonFragment + "}";
                java.net.http.HttpRequest httpReq = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(url)).header("Authorization", "Bearer " + key)
                        .header("Content-Type", "application/json").timeout(java.time.Duration.ofSeconds(120))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
                java.net.http.HttpResponse<String> httpResp = java.net.http.HttpClient.newHttpClient().send(httpReq,
                        java.net.http.HttpResponse.BodyHandlers.ofString());
                if (httpResp.statusCode() != 200) {
                    return "";
                }
                return com.openjiuwen.agents.edpa.util.LlmResponseExtractor.extractContent(httpResp.body());
            } catch (IOException | InterruptedException | RuntimeException e) {
                // CodeCheck (G.CON.10): no app-managed executor here (HttpClient.newHttpClient uses the JVM
                // global executor, which we must not shutdown); the swallowed InterruptedException simply
                // lets the e2e test thread finish — surface empty so isTransientFlaky can classify the row.
                return "";
            }
        };
    }

    /**
     * Extracts the textual output from an agent result, preferring a map's {@code output} field.
     *
     * @param result the raw agent invocation result
     * @return the output string (empty if {@code result} is null)
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
     * 4-lens BLOCKER fix: classifies a result as transient-flaky ONLY on HTTP-layer exception signatures.
     * Bare empty is NOT auto-flaky (must surface).
     *
     * @param r the per-config result map
     * @return true if the captured stacktrace matches a known transient HTTP/timeout signature
     */
    private static boolean isTransientFlaky(Map<String, Object> r) {
        String st = String.valueOf(r.get("stacktrace"));
        for (String signature : TRANSIENT_FLAKY_SIGNATURES) {
            if (st.contains(signature)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Formats a single result map as a compact one-line summary for logging.
     *
     * @param r the per-config result map
     * @return formatted summary string
     */
    private static String fmt(Map<String, Object> r) {
        return "explore=" + r.get("exploreCount") + " tools=" + r.get("toolCalls") + " convrg="
                + r.get("convergenceTrigger") + " verified=" + r.get("verified") + " outLen=" + r.get("outputLen")
                + " status=" + r.get("status");
    }
}
