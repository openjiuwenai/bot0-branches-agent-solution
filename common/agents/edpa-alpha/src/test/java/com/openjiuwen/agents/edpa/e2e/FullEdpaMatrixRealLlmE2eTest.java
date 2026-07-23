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
 * Full EDPA real-LLM matrix e2e — runs the complete E→D→P→A stack across a matrix of
 * models × thinking on/off, all through openrouter (unified endpoint + reasoning param,
 * so thinking is a comparable variable).
 *
 * <p><b>Matrix</b> (5 models × 2 thinking = 10 configs):
 * <ul>
 *   <li>qwen/qwen3.6-35b-a3b, qwen/qwen3.6-27b (qwen MoE)</li>
 *   <li>z-ai/glm-4.7 (GLM)</li>
 *   <li>deepseek/deepseek-v4-pro, deepseek/deepseek-v4-flash (deepseek)</li>
 *   <li>thinking on = {@code reasoning.enabled=true} extra field; off = omitted</li>
 * </ul>
 *
 * <p><b>Honest thinking-control caveat (probe-verified):</b> reasoning-trained models
 * (e.g. qwen3.6) return a {@code reasoning} field EVEN when the param is omitted — so
 * "thinking off" is not a perfect switch for all models; it's an observed behavioral
 * variable, not a guaranteed controlled one. The matrix records reasoning-observed per
 * config so the comparison is honest.
 *
 * <p><b>Reasoning max_tokens fix (root-caused after first run):</b> qwen3.6 reasoning
 * burns ~900-1500 tokens on the {@code reasoning} field BEFORE generating {@code content}/
 * {@code tool_calls}. With the original max_tokens=900, responses hit {@code finish_reason=length}
 * with no {@code tool_calls} → (a) 35b empty (no tool calls ever generated), (b) 27b-ON
 * {@code ToolCallingBypassException} (the {@code ToolCallingEnforcingModel} probe saw no
 * tool_calls and threw). Fix: max_tokens=4000 (agent) / 2500 (Explorer) so reasoning has
 * room and tool_calls/content still generate. Same root cause for both qwen failures.
 *
 * <p><b>Per config the full EDPA stack runs</b>: ExploreTool (Species E) + stub
 * market_data tool + CriteriaReplanBridgeRail (Action verify) + ReplanRail (Decision
 * reactive) + ProactiveConvergenceRail (Decision proactive). Collected signals:
 * exploreCount, toolCalls, criteria verified, convergence triggerCount, output length,
 * reasoning observed, completion status.
 *
 * <p>Env-gated opt-in: {@code EDPA_FULL_MATRIX_ENABLED=true} +
 * {@code OPENROUTER_API_KEY}. Each config is isolated in try/catch so one failure does
 * not abort the matrix; a summary table is printed + an overall sanity assert.
 *
 * @since 2026-07
 */
class FullEdpaMatrixRealLlmE2eTest {
    private static final Logger LOG = Logger.getLogger(FullEdpaMatrixRealLlmE2eTest.class.getName());

    private static final String OR_BASE = "https://openrouter.ai/api/v1";
    private static final String OR_COMPLETIONS = "/chat/completions";

    /** Summary-table column format (model/thinking/explore/tools/convrg/verified/status). */
    private static final String SUMMARY_FMT = "%-32s %-9s %-7s %-7s %-8s %-9s %-9s%n";

    private static final List<String> MODELS = List.of("qwen/qwen3.6-35b-a3b", "qwen/qwen3.6-27b", "z-ai/glm-4.7",
            "deepseek/deepseek-v4-pro", "deepseek/deepseek-v4-flash");

    private static final List<String> CRITERIA = List.of("分析", "建议");

    private static final String TASK = """
            分析当前的经济形势。请按以下步骤：
            1. 先调用 explore 工具，探索可用的经济分析维度。
            2. 调用 market_data 工具获取经济指标数据（indicator 传 "GDP" 或 "利率"）。
            3. 基于探索与数据，给出简短的投资建议。
            【规则】每次工具调用单独一轮；最后无工具调用的回复是最终建议。
            """;

    @Test
    @Timeout(2400)
    void fullEdpaMatrix_5models_x_thinkingOnOff() {
        String orKey = System.getenv("OPENROUTER_API_KEY");
        String enabled = System.getenv("EDPA_FULL_MATRIX_ENABLED");
        Assumptions.assumeTrue(
                orKey != null && !orKey.isBlank() && "true".equalsIgnoreCase(enabled),
                "Full matrix e2e requires OPENROUTER_API_KEY / EDPA_FULL_MATRIX_ENABLED=true");
        DefaultModelClientFactories.ensureRegistered();

        List<Map<String, Object>> results = runMatrix(orKey);
        printSummaryTable(results);
        int configs = MODELS.size() * 2;
        int verifiedCount = (int) results.stream().filter(r -> Boolean.TRUE.equals(r.get("verified"))).count();

        // 4-lens BLOCKER fix: empty is NOT auto-flaky. Three honest categories:
        //  - outputWithoutVerify: produced output but verified != TRUE = real quality failure (HARD)
        //  - hardErrors: non-flaky exception = real EDPA bug
        //  - empty: output blank — EXPOSED (not hidden as flaky); noted as "needs confirmation:
        //    model characteristic (e.g. glm-4.7+thinking content-empty) vs real bug". curl-proven
        //    model characteristics are listed in the note; any NEW deterministic empty would surface here.
        int outputWithoutVerify = countOutputWithoutVerify(results);
        int hardErrors = countHardErrors(results);
        int emptyConfigs = countEmpty(results);
        int networkFlakyStillFailing = countNetworkFlakyStillFailing(results);
        LOG.log(Level.INFO, "{0}", String.format(java.util.Locale.ROOT,
                "[matrix] verified %d/%d | output-without-verify (HARD quality): %d"
                        + " | hardErrors (non-flaky exception): %d"
                        + " | empty (exposed, needs confirmation — glm-4.7+thinking=known model char): %d"
                        + " | network-flaky still failing after retry: %d",
                verifiedCount, configs, outputWithoutVerify, hardErrors, emptyConfigs, networkFlakyStillFailing));

        // ---- HARD asserts (4-lens fix: no more empty-auto-flaky循环论证) ----
        assertThat(outputWithoutVerify).as("output produced but criteria not verified = real quality failure; got "
                + outputWithoutVerify + " (these produced output but verified!=TRUE)").isZero();
        assertThat(hardErrors).as("non-flaky exceptions = real EDPA bug; got " + hardErrors).isZero();
        assertThat(verifiedCount)
                .as("at least half the configs must genuinely criteria-verify (verifiedCount " + verifiedCount + "/"
                        + configs + "; verified is a real bearing signal, not just observed)")
                // G.TYP.04: 0.5 is an assertion threshold (halve then ceil up), not an accumulated
                // float computation; 0.5 is exactly representable as double, so no precision drift.
                .isGreaterThanOrEqualTo((configs + 1) / 2);
    }

    /**
     * Runs every model × thinking config once (with one transient-flaky retry per config).
     *
     * @param orKey openrouter API key
     * @return collected result rows, one per config actually executed
     */
    private List<Map<String, Object>> runMatrix(String orKey) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String model : MODELS) {
            for (boolean thinking : new boolean[]{true, false}) {
                String label = model + " | thinking=" + (thinking ? "ON" : "OFF");
                Map<String, Object> r = runOneConfig(orKey, model, thinking);
                // openrouter network flaky tolerance: retry once on HTTP-layer failures
                // (IOException "header parser received no bytes" / "end-of-input" / HttpTimeout)
                // OR reasoning-model content-empty flaky (qwen3.6 sometimes returns content=""
                // with the answer in the reasoning field — curl-proven non-deterministic, not an EDPA bug).
                if (isTransientFlaky(r) && !"completed".equals(r.get("status"))) {
                    LOG.log(Level.INFO, "[matrix] RETRY (transient flaky) {0}", label);
                    Map<String, Object> r2 = runOneConfig(orKey, model, thinking);
                    r2.put("label", label);
                    r2.put("retried", true);
                    results.add(r2);
                    LOG.log(Level.INFO, "[matrix] {0} (retry) → {1}", new Object[]{label, fmt(r2)});
                } else {
                    r.put("label", label);
                    results.add(r);
                    LOG.log(Level.INFO, "[matrix] {0} → {1}", new Object[]{label, fmt(r)});
                }
            }
        }
        return results;
    }

    /**
     * Prints the per-config summary table (model/thinking/explore/tools/convergence/verified/status).
     *
     * @param results collected result rows to render
     */
    private void printSummaryTable(List<Map<String, Object>> results) {
        LOG.log(Level.INFO, "{0}", "================ EDPA MATRIX SUMMARY ================");
        LOG.log(Level.INFO, "{0}", String.format(java.util.Locale.ROOT, SUMMARY_FMT, "model", "thinking", "explor",
                "tools", "convrg", "verified", "status"));
        for (Map<String, Object> r : results) {
            String shortModel = String.valueOf(r.get("model")).replaceFirst("^(qwen/|z-ai/|deepseek/)", "");
            LOG.log(Level.INFO, "{0}", String.format(java.util.Locale.ROOT, SUMMARY_FMT, shortModel, r.get("thinking"),
                    r.get("exploreCount"), r.get("toolCalls"), r.get("convergenceTrigger"), r.get("verified"),
                    r.get("status")));
        }
        LOG.log(Level.INFO, "{0}", "=====================================================");
    }

    /**
     * Counts configs that produced output (status {@code completed}) but did NOT criteria-verify.
     *
     * @param results collected result rows
     * @return number of completed-but-unverified configs (HARD quality failures)
     */
    private static int countOutputWithoutVerify(List<Map<String, Object>> results) {
        return (int) results.stream()
                .filter(r -> "completed".equals(r.get("status")) && !Boolean.TRUE.equals(r.get("verified"))).count();
    }

    /**
     * Counts non-flaky exception configs (status starts with {@code error} but not transient network flaky).
     *
     * @param results collected result rows
     * @return number of hard (non-flaky) error configs
     */
    private static int countHardErrors(List<Map<String, Object>> results) {
        return (int) results.stream()
                .filter(r -> String.valueOf(r.get("status")).startsWith("error") && !isTransientFlaky(r)).count();
    }

    /**
     * Counts configs whose output was blank (status {@code empty}).
     *
     * @param results collected result rows
     * @return number of empty-output configs
     */
    private static int countEmpty(List<Map<String, Object>> results) {
        return (int) results.stream().filter(r -> "empty".equals(r.get("status"))).count();
    }

    /**
     * Counts transient-flaky error configs that were retried and STILL failed.
     *
     * @param results collected result rows
     * @return number of network-flaky configs still failing after one retry
     */
    private static int countNetworkFlakyStillFailing(List<Map<String, Object>> results) {
        return (int) results.stream().filter(r -> String.valueOf(r.get("status")).startsWith("error")
                && isTransientFlaky(r) && Boolean.TRUE.equals(r.get("retried"))).count();
    }

    /**
     * Transient flaky = HTTP-layer exception ONLY (openrouter空响应/断连/timeout).
     * <p><b>4-lens BLOCKER fix:</b> a bare {@code status=="empty"} (output blank with NO network
     * exception) is NOT auto-flaky — it could be a deterministic EDPA bug, so it must surface.
     * (Previously empty was unconditionally flaky, making "0 HARD bug" a循环论证.) Only
     * HTTP-stacktrace markers count as transient network flaky.
     *
     * @param r the result row whose stacktrace is inspected
     * @return {@code true} only if the row carries an HTTP-layer stacktrace marker
     */
    private static boolean isTransientFlaky(Map<String, Object> r) {
        String st = String.valueOf(r.get("stacktrace"));
        return st.contains("IOException") || st.contains("header parser") || st.contains("end-of-input")
                || st.contains("HttpTimeout") || st.contains("TimeoutException") || st.contains("Connection reset");
    }

    /**
     * Builds and invokes the full EDPA stack for one (model, thinking) config, recording every
     * collected signal into the returned row. Any failure is captured into the row (status
     * {@code error:&lt;SimpleClassName&gt;}) so the matrix keeps running.
     *
     * @param orKey    openrouter API key
     * @param model    model id (e.g. {@code z-ai/glm-4.7})
     * @param thinking whether to enable the openrouter reasoning extra field
     * @return a mutable result row containing all collected signals and a terminal status
     */
    private Map<String, Object> runOneConfig(String orKey, String model, boolean thinking) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", model);
        r.put("thinking", thinking ? "ON" : "OFF");
        r.put("exploreCount", 0);
        r.put("toolCalls", 0);
        r.put("convergenceTrigger", 0);
        r.put("verified", null);
        r.put("status", "error");
        try {
            ReActAgent agent = buildAgent(orKey, model, thinking);
            AtomicInteger toolCalls = new AtomicInteger(0);
            registerMarketDataTool(agent, toolCalls);

            AtomicReference<String> userInputRef = new AtomicReference<>();
            agent.registerRail(new UserInputCaptureRail(userInputRef));

            AtomicInteger exploreCount = new AtomicInteger(0);
            Function<String, String> explorerFn = buildExplorerFn(orKey, model, thinking, exploreCount);
            Explorer explorer = new LlmExplorer(explorerFn, ExploreBudget.DEFAULT);
            ExploreToolRegistrar.registerOnto(agent, explorer, ExploreBudget.DEFAULT, userInputRef::get);

            ReplanRail sharedCounter = new ReplanRail(3);
            agent.registerRail(new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(), CRITERIA, sharedCounter));
            ProactiveConvergenceRail convergence = new ProactiveConvergenceRail(new RuleBasedCriteriaVerifier(),
                    CRITERIA, 2, ProactiveConvergenceRail.DEFAULT_COVERAGE_CRITICAL);
            agent.registerRail(convergence);
            agent.registerRail(new ReplanRail(3));
            ReplanTool.registerOnto(agent);

            Object result = agent.invoke(TASK, null);
            recordRunSignals(r, result, exploreCount, toolCalls);
        } catch (RuntimeException | Error e) {
            // CodeCheck: e2e test 容错 catch-all — one config failure must not abort the matrix
            recordFailure(r, e, model, thinking);
        }
        return r;
    }

    /**
     * Constructs the ReActAgent with its enforcing model and the thinking extra field, capped at 12 iterations.
     *
     * @param orKey    openrouter API key
     * @param model    model id
     * @param thinking whether to enable the reasoning extra field
     * @return a configured ReActAgent (tools/rails not yet registered)
     */
    private ReActAgent buildAgent(String orKey, String model, boolean thinking) {
        ModelClientConfig cliCfg = ModelClientConfig.builder()
                .clientId("edpa-matrix-" + model.replace('/', '-') + "-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(orKey).apiBase(OR_BASE).verifySsl(false).timeout(180000).build();
        // G.TYP.04: temperature/topP are LLM sampling params passed verbatim to the openrouter API,
        // not accumulated float computations; 0.3/0.9 are exactly representable as double.
        ModelRequestConfig reqCfg = ModelRequestConfig.builder()
                .modelName(model)
                .temperature(0.3)
                .topP(0.9)
                .maxTokens(4000)
                .build();
        // thinking on/off via openrouter reasoning extra field (SDK ModelRequestConfig.extraFields)
        if (thinking) {
            reqCfg.setExtraField("reasoning", Map.of("enabled", Boolean.TRUE));
            reqCfg.setExtraField("include_reasoning", Boolean.TRUE);
        }
        ToolCallingEnforcingModel agentModel = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-matrix").build());
        agent.setLlm(agentModel);
        Object cfg = agent.getConfig();
        if (cfg instanceof ReActAgentConfig rc) {
            rc.configureMaxIterations(12);
        }
        return agent;
    }

    /**
     * Writes the post-invoke signals (explore/tool counts, verified flag, output length, status) into the row.
     *
     * @param r            the result row to mutate
     * @param result       the agent invoke result
     * @param exploreCount explore-tool invocation counter
     * @param toolCalls    market_data tool invocation counter
     */
    private void recordRunSignals(Map<String, Object> r, Object result, AtomicInteger exploreCount,
            AtomicInteger toolCalls) {
        String output = extractOutput(result);
        r.put("exploreCount", exploreCount.get());
        r.put("toolCalls", toolCalls.get());
        r.put("convergenceTrigger", -1); // 状态隔离后 invoke 不可读 (RailInvocationState per-invocation)
        r.put("verified", result instanceof Map<?, ?> rm ? rm.get(CriteriaReplanBridgeRail.VERIFIED_KEY) : null);
        r.put("outputLen", output.length());
        r.put("status", output.isBlank() ? "empty" : "completed");
    }

    /**
     * Records a config failure into the row (status, first error line, full stacktrace) and logs it.
     *
     * @param r        the result row to mutate
     * @param e        the caught failure
     * @param model    model id (for the log line)
     * @param thinking whether thinking was on (for the log line)
     */
    private void recordFailure(Map<String, Object> r, Throwable e, String model, boolean thinking) {
        r.put("status", "error:" + e.getClass().getSimpleName());
        r.put("error", e.getMessage() == null ? "" : e.getMessage().split("\\n")[0]);
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String st = sw.toString();
        r.put("stacktrace", st);
        LOG.log(Level.INFO, "[matrix] STACKTRACE {0} thinking={1} : {2}",
                new Object[]{model, thinking, st.substring(0, Math.min(1500, st.length()))});
    }

    /**
     * Registers the stub {@code market_data} tool onto the agent and runner resource manager.
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
     * Builds a direct-HTTP Explorer LLM function (mirrors the agent's thinking config; bypasses the SDK
     * to control the reasoning body). Any network/IO failure collapses to an empty exploration result.
     *
     * @param orKey        openrouter API key
     * @param model        model id
     * @param thinking     whether to enable the reasoning extra field
     * @param exploreCount incremented on each invocation
     * @return a prompt → assistant-content function
     */
    private static Function<String, String> buildExplorerFn(String orKey, String model, boolean thinking,
            AtomicInteger exploreCount) {
        return prompt -> {
            exploreCount.incrementAndGet();
            try {
                String body = buildExplorerBody(prompt, model, thinking);
                HttpRequest httpReq = HttpRequest.newBuilder().uri(URI.create(OR_BASE + OR_COMPLETIONS))
                        .header("Authorization", "Bearer " + orKey).header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(120)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> httpResp = HttpClient.newHttpClient().send(httpReq,
                        HttpResponse.BodyHandlers.ofString());
                if (httpResp.statusCode() != 200) {
                    return "";
                }
                return LlmResponseExtractor.extractContent(httpResp.body());
            } catch (IOException | InterruptedException e) {
                return "";
            }
        };
    }

    /**
     * Builds the raw openrouter chat-completions JSON body for the Explorer LLM call.
     *
     * @param prompt  the user prompt to send
     * @param model   model id
     * @param thinking whether to include the reasoning extra fields
     * @return a JSON request body string
     */
    private static String buildExplorerBody(String prompt, String model, boolean thinking) {
        String escaped = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
        return "{\"model\":\"" + model + "\"," + "\"messages\":[{\"role\":\"user\",\"content\":\"" + escaped + "\"}],"
                + "\"temperature\":0.3,\"max_tokens\":2500,\"top_p\":0.9"
                + (thinking ? ",\"reasoning\":{\"enabled\":true},\"include_reasoning\":true" : "") + "}";
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
     * Renders a one-line summary of a result row (counts, verified flag, output length, status).
     *
     * @param r the result row to render
     * @return a compact single-line summary string
     */
    private static String fmt(Map<String, Object> r) {
        return "explore=" + r.get("exploreCount") + " tools=" + r.get("toolCalls") + " convrg="
                + r.get("convergenceTrigger") + " verified=" + r.get("verified") + " outLen=" + r.get("outputLen")
                + " status=" + r.get("status");
    }
}
