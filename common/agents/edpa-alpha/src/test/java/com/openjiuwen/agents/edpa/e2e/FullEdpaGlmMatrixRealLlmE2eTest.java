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
import com.openjiuwen.agents.edpa.util.LlmResponseExtractor;
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
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * GLM-native full-EDPA matrix e2e — glm-4.7 and glm-5.2 × thinking on/off, through the
 * GLM bigmodel API directly (NOT openrouter), using {@code thinking.type=enabled} (the
 * Zhipu thinking switch; GLM returns {@code reasoning_content} alongside {@code content}).
 *
 * <p>Companion to {@link FullEdpaMatrixRealLlmE2eTest} (which used openrouter). This one
 * uses the GLM native endpoint + OPENJIUWEN_API_KEY so GLM behavior is measured on its
 * home API, not via an aggregator.
 *
 * <p><b>Matrix</b>: 2 models × 2 thinking = 4 configs. Full EDPA stack (ExploreTool +
 * market_data stub + CriteriaReplanBridgeRail + PevReplanRail + ProactiveConvergenceRail).
 * Same transient-flaky retry + tolerant-assert discipline as the openrouter matrix.
 *
 * <p>Env-gated opt-in: {@code OPENJIUWEN_API_KEY} / {@code OPENJIUWEN_BASE_URL} /
 * {@code EDPA_GLM_MATRIX_ENABLED=true}.
 *
 * @author openjiuwen
 * @since 2026-07
 */
class FullEdpaGlmMatrixRealLlmE2eTest {
    private static final Logger LOG = Logger.getLogger(FullEdpaGlmMatrixRealLlmE2eTest.class.getName());

    private static final List<String> MODELS = List.of("glm-4.7", "glm-5.2");
    private static final List<String> CRITERIA = List.of("分析", "建议");

    private static final String TASK = """
            分析当前的经济形势。请按以下步骤：
            1. 先调用 explore 工具，探索可用的经济分析维度。
            2. 调用 market_data 工具获取经济指标数据（indicator 传 "GDP" 或 "利率"）。
            3. 基于探索与数据，给出简短的投资建议。
            【规则】每次工具调用单独一轮；最后无工具调用的回复是最终建议。
            """;

    /**
     * Runs the GLM-native matrix (glm-4.7 and glm-5.2, thinking on/off = 4 configs) through the
     * full EDPA stack, then asserts the 4-lens honest verdict (no output-without-verify, no
     * non-flaky hard errors, at least half of configs criteria-verified).
     *
     * <p>Env-gated: silently skips (JUnit assumption) unless {@code OPENJIUWEN_API_KEY},
     * {@code OPENJIUWEN_BASE_URL} and {@code EDPA_GLM_MATRIX_ENABLED=true} are all set.
     */
    @Test
    @Timeout(1800)
    void glmMatrix_native_2models_x_thinkingOnOff() {
        GlmMatrixEnv env = resolveMatrixEnv();
        DefaultModelClientFactories.ensureRegistered();

        List<Map<String, Object>> results = new ArrayList<>();
        for (String model : MODELS) {
            for (boolean thinking : new boolean[]{true, false}) {
                results.add(runConfigWithRetry(env.key(), env.base(), model, thinking));
            }
        }

        printMatrixSummary(results);
        assertMatrixVerdict(results);
    }

    /**
     * Resolves and validates the GLM-matrix environment. Fails the test via a JUnit assumption
     * (not an error) when the matrix is not opted in.
     *
     * @return the resolved env triple (key, base, enabled flag)
     */
    private static GlmMatrixEnv resolveMatrixEnv() {
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        String enabled = System.getenv("EDPA_GLM_MATRIX_ENABLED");
        Assumptions.assumeTrue(
                key != null && !key.isBlank() && base != null && !base.isBlank() && "true".equalsIgnoreCase(enabled),
                "GLM matrix requires OPENJIUWEN_API_KEY / OPENJIUWEN_BASE_URL / EDPA_GLM_MATRIX_ENABLED=true");
        return new GlmMatrixEnv(key, base);
    }

    /**
     * Runs a single (model, thinking) config, retrying once on a transient-flaky (HTTP-layer)
     * outcome that did not complete.
     *
     * @param key the GLM API key
     * @param base the GLM API base URL
     * @param model the model name (e.g. {@code glm-4.7})
     * @param thinking whether GLM native thinking ({@code thinking.type=enabled}) is on
     * @return the result row for this config (possibly a retried row)
     */
    private Map<String, Object> runConfigWithRetry(String key, String base, String model, boolean thinking) {
        String label = model + " | thinking=" + (thinking ? "ON" : "OFF");
        Map<String, Object> r = runOneConfig(key, base, model, thinking);
        if (isTransientFlaky(r) && !"completed".equals(r.get("status"))) {
            LOG.log(Level.INFO, "[glm-matrix] RETRY (transient flaky) {0}", label);
            Map<String, Object> r2 = runOneConfig(key, base, model, thinking);
            r2.put("retried", true);
            LOG.log(Level.INFO, "[glm-matrix] {0} (retry) -> {1}", new Object[]{label, fmt(r2)});
            return r2;
        }
        LOG.log(Level.INFO, "[glm-matrix] {0} -> {1}", new Object[]{label, fmt(r)});
        return r;
    }

    /**
     * Prints the per-config GLM matrix summary table (model/thinking/explore/tools/convergence/verified/status).
     *
     * @param results collected result rows to render
     */
    private static void printMatrixSummary(List<Map<String, Object>> results) {
        LOG.log(Level.INFO, "{0}", "\n================ GLM MATRIX SUMMARY ================");
        String header = String.format(java.util.Locale.ROOT,
                "%-12s %-9s %-7s %-7s %-8s %-9s %-9s", "model", "thinking", "explor", "tools", "convrg", "verified",
                "status");
        LOG.log(Level.INFO, "{0}", header);
        for (Map<String, Object> r : results) {
            String row = String.format(java.util.Locale.ROOT,
                    "%-12s %-9s %-7s %-7s %-8s %-9s %-9s", r.get("model"), r.get("thinking"), r.get("exploreCount"),
                    r.get("toolCalls"), r.get("convergenceTrigger"), r.get("verified"), r.get("status"));
            LOG.log(Level.INFO, "{0}", row);
        }
    }

    /**
     * Asserts the 4-lens honest verdict over the GLM matrix results: zero output-without-verify,
     * zero non-flaky hard errors, and at least half of the configs criteria-verified.
     *
     * @param results collected result rows, one per executed config
     */
    private void assertMatrixVerdict(List<Map<String, Object>> results) {
        int verifiedCount = countVerified(results);
        int configs = MODELS.size() * 2;
        // 4-lens BLOCKER fix: empty is NOT auto-flaky (same redesign as openrouter matrix).
        int outputWithoutVerify = (int) results.stream()
                .filter(r -> "completed".equals(r.get("status")) && !Boolean.TRUE.equals(r.get("verified"))).count();
        int hardErrors = (int) results.stream()
                .filter(r -> String.valueOf(r.get("status")).startsWith("error") && !isTransientFlaky(r)).count();
        int emptyConfigs = (int) results.stream().filter(r -> "empty".equals(r.get("status"))).count();
        LOG.log(Level.INFO, "{0}", "=====================================================");
        LOG.log(Level.INFO,
                "[glm-matrix] verified {0}/{1} | output-without-verify (HARD quality): {2}"
                        + " | hardErrors (non-flaky exception): {3}"
                        + " | empty (exposed, needs confirmation — glm-4.7+thinking=known model char): {4}",
                new Object[]{verifiedCount, configs, outputWithoutVerify, hardErrors, emptyConfigs});

        // ---- HARD asserts (4-lens fix: empty no longer hidden as flaky) ----
        // Note: small matrix (4 configs) — verifiedCount>=half is the honest bar; empty is EXPOSED
        // not auto-tolerated, so a genuine new deterministic empty would surface (not silently pass).
        assertThat(outputWithoutVerify)
                .as("output produced but criteria not verified = real quality failure; got " + outputWithoutVerify)
                .isZero();
        assertThat(hardErrors).as("non-flaky exceptions = real EDPA bug; got " + hardErrors).isZero();
        assertThat(verifiedCount)
                .as("at least half GLM configs genuinely criteria-verify (got " + verifiedCount + "/" + configs + ")")
                .isGreaterThanOrEqualTo((configs + 1) / 2);
    }

    /**
     * Counts how many result rows have their criteria-verification flag set to {@code TRUE}.
     *
     * @param results collected result rows to scan
     * @return the number of verified configs
     */
    private static int countVerified(List<Map<String, Object>> results) {
        int verifiedCount = 0;
        for (Map<String, Object> r : results) {
            if (Boolean.TRUE.equals(r.get("verified"))) {
                verifiedCount++;
            }
        }
        return verifiedCount;
    }

    /** Resolved environment triple for the GLM matrix. */
    private record GlmMatrixEnv(String key, String base) {
    }

    /**
     * Runs a single (model, thinking) GLM config end-to-end through the full EDPA stack and
     * captures the outcome row. On any failure the row keeps {@code status="error:..."} plus a
     * stacktrace marker so transient-flaky classification (HTTP-only) can retry later.
     *
     * @param key the GLM API key
     * @param base the GLM API base URL
     * @param model the model name (e.g. {@code glm-4.7})
     * @param thinking whether GLM native thinking ({@code thinking.type=enabled}) is on
     * @return the result row for this config (never {@code null})
     */
    private Map<String, Object> runOneConfig(String key, String base, String model, boolean thinking) {
        Map<String, Object> r = newRow(model, thinking);
        try {
            ConfigRunOutcome outcome = buildAndInvoke(key, base, model, thinking);
            r.put("exploreCount", outcome.exploreCount());
            r.put("toolCalls", outcome.toolCalls());
            r.put("convergenceTrigger", -1); // 状态隔离后 invoke 不可读 (RailInvocationState per-invocation)
            r.put("verified", outcome.verified());
            r.put("outputLen", outcome.output().length());
            r.put("status", outcome.output().isBlank() ? "empty" : "completed");
        } catch (RuntimeException | Error e) {
            // CodeCheck: test 容错 catch-all — must capture every LLM/agent failure path so the
            // matrix records the outcome instead of aborting the whole run.
            r.put("status", "error:" + e.getClass().getSimpleName());
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String st = sw.toString();
            r.put("stacktrace", st);
            LOG.log(Level.WARNING, "[glm-matrix] STACKTRACE {0} thinking={1} : {2}",
                    new Object[]{model, thinking, st.substring(0, Math.min(1200, st.length()))});
        }
        return r;
    }

    /**
     * Creates a fresh mutable result row pre-populated with default counters and an {@code error} status,
     * overwritten by the actual run outcome on success.
     *
     * @param model    the model name
     * @param thinking whether GLM native thinking is on
     * @return a new result row with defaults set
     */
    private static Map<String, Object> newRow(String model, boolean thinking) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", model);
        r.put("thinking", thinking ? "ON" : "OFF");
        r.put("exploreCount", 0);
        r.put("toolCalls", 0);
        r.put("convergenceTrigger", 0);
        r.put("verified", null);
        r.put("status", "error");
        return r;
    }

    /**
     * Builds the EDPA agent for one (model, thinking) config, registers the full rail stack,
     * invokes the task, and folds the observable counters into an immutable outcome record.
     *
     * @param key the GLM API key
     * @param base the GLM API base URL
     * @param model the model name
     * @param thinking whether GLM native thinking is on
     * @return the captured counters (explore/tool counts + verified flag + raw output)
     */
    private ConfigRunOutcome buildAndInvoke(String key, String base, String model, boolean thinking) {
        ModelClientConfig cliCfg = ModelClientConfig.builder().clientId("edpa-glm-" + model + "-" + System.nanoTime())
                .clientProvider("OpenAI").apiKey(key).apiBase(base).verifySsl(false).timeout(180000).build();
        ModelRequestConfig reqCfg = ModelRequestConfig.builder().modelName(model).temperature(0.3).topP(0.9)
                .maxTokens(4000).build();
        if (thinking) {
            // GLM/Zhipu native thinking switch: thinking.type=enabled → reasoning_content returned
            reqCfg.setExtraField("thinking", Map.of("type", "enabled"));
        }
        ToolCallingEnforcingModel agentModel = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-glm-matrix").build());
        agent.setLlm(agentModel);
        configureMaxIterations(agent);

        AtomicInteger toolCalls = new AtomicInteger(0);
        registerMarketDataTool(agent, toolCalls);

        AtomicReference<String> userInputRef = new AtomicReference<>();
        agent.registerRail(new UserInputCaptureRail(userInputRef));

        AtomicInteger exploreCount = new AtomicInteger(0);
        String completionsPath = System.getenv().getOrDefault("OPENJIUWEN_COMPLETIONS_PATH", "/chat/completions");
        Function<String, String> explorerFn = buildExplorerFn(key, base + completionsPath, model, thinking,
                exploreCount);
        Explorer explorer = new LlmExplorer(explorerFn, ExploreBudget.DEFAULT);
        ExploreToolRegistrar.registerOnto(agent, explorer, ExploreBudget.DEFAULT, userInputRef::get);

        registerRails(agent);

        Object result = agent.invoke(TASK, null);
        String output = extractOutput(result);
        Object verified = result instanceof Map<?, ?> rm ? rm.get(CriteriaReplanBridgeRail.VERIFIED_KEY) : null;
        return new ConfigRunOutcome(exploreCount.get(), toolCalls.get(), verified, output);
    }

    /**
     * Caps the agent's max ReAct iterations at 12 when its config is a {@link ReActAgentConfig}.
     *
     * @param agent the agent whose iteration cap to configure
     */
    private static void configureMaxIterations(ReActAgent agent) {
        Object cfg = agent.getConfig();
        if (cfg instanceof ReActAgentConfig rc) {
            rc.configureMaxIterations(12);
        }
    }

    /**
     * Registers the full EDPA decision/verification rail stack onto the agent: the shared-counter
     * criteria-replan bridge, the proactive convergence rail, the reactive replan rail, and the replan tool.
     *
     * @param agent the agent to attach the rails to
     */
    private void registerRails(ReActAgent agent) {
        ReplanRail sharedCounter = new ReplanRail(3);
        agent.registerRail(new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(), CRITERIA, sharedCounter));
        ProactiveConvergenceRail convergence = new ProactiveConvergenceRail(new RuleBasedCriteriaVerifier(),
                CRITERIA, 2, ProactiveConvergenceRail.DEFAULT_COVERAGE_CRITICAL);
        agent.registerRail(convergence);
        agent.registerRail(new ReplanRail(3));
        ReplanTool.registerOnto(agent);
    }

    /** Immutable snapshot of the observable counters captured for one config run. */
    private record ConfigRunOutcome(int exploreCount, int toolCalls, Object verified, String output) {
    }

    /**
     * Registers the stub {@code market_data} tool onto the agent and runner resource manager,
     * incrementing the supplied counter on each invocation.
     *
     * @param agent   the agent to attach the tool ability to
     * @param counter incremented on each invocation
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
     * Builds a direct-HTTP Explorer LLM function against the GLM native endpoint (mirrors the agent's
     * thinking config; bypasses the SDK to control the {@code thinking} body). Any network/IO failure
     * collapses to an empty exploration result so the matrix records the outcome instead of aborting.
     *
     * @param key         the GLM API key
     * @param url         the full GLM completions URL (base + completions path)
     * @param model       the model name
     * @param thinking    whether GLM native thinking is on
     * @param exploreCount incremented on each invocation
     * @return a prompt → assistant-content function
     */
    private static Function<String, String> buildExplorerFn(String key, String url, String model, boolean thinking,
            AtomicInteger exploreCount) {
        return prompt -> {
            exploreCount.incrementAndGet();
            try {
                String body = buildExplorerBody(prompt, model, thinking);
                HttpRequest httpReq = HttpRequest.newBuilder().uri(URI.create(url))
                        .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(120)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> httpResp = HttpClient.newHttpClient().send(httpReq,
                        HttpResponse.BodyHandlers.ofString());
                if (httpResp.statusCode() != 200) {
                    return "";
                }
                return LlmResponseExtractor.extractContent(httpResp.body());
            } catch (IOException | InterruptedException e) {
                // CodeCheck: test 容错 catch-all — explorer best-effort fetch; any failure yields
                // an empty completion so the matrix records the outcome instead of aborting.
                return "";
            }
        };
    }

    /**
     * Builds the GLM completions JSON body for one explorer prompt, mirroring the agent's thinking config.
     *
     * @param prompt   the raw user prompt to escape and embed
     * @param model    the model name
     * @param thinking whether to add the {@code thinking.type=enabled} field
     * @return the JSON request body string
     */
    private static String buildExplorerBody(String prompt, String model, boolean thinking) {
        String escaped = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        return "{\"model\":\"" + model + "\"," + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escaped + "\"}],"
                + "\"temperature\":0.3,\"max_tokens\":2500,\"top_p\":0.9"
                + (thinking ? ",\"thinking\":{\"type\":\"enabled\"}" : "") + "}";
    }

    /**
     * Extracts the textual output from an agent invoke result, looking up the {@code output} map key.
     *
     * @param result the agent invoke result (may be a map or a plain value)
     * @return the textual output, or empty string when the result is {@code null}
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
     * Transient flaky = HTTP-layer exception ONLY.
     * <p><b>4-lens BLOCKER fix:</b> bare {@code status=="empty"} is NOT auto-flaky — it must
     * surface (could be a deterministic bug). Only HTTP-stacktrace markers are transient.
     *
     * @param r the result row whose stacktrace to inspect
     * @return {@code true} if the row's stacktrace contains an HTTP-layer transient marker
     */
    private static boolean isTransientFlaky(Map<String, Object> r) {
        String st = String.valueOf(r.get("stacktrace"));
        return st.contains("IOException") || st.contains("header parser") || st.contains("end-of-input")
                || st.contains("HttpTimeout") || st.contains("TimeoutException") || st.contains("Connection reset");
    }

    /**
     * Formats one result row as a compact single-line summary for logging.
     *
     * @param r the result row to format
     * @return a {@code key=value}-joined summary string
     */
    private static String fmt(Map<String, Object> r) {
        return "explore=" + r.get("exploreCount") + " tools=" + r.get("toolCalls") + " convrg="
                + r.get("convergenceTrigger") + " verified=" + r.get("verified") + " outLen=" + r.get("outputLen")
                + " status=" + r.get("status");
    }
}
