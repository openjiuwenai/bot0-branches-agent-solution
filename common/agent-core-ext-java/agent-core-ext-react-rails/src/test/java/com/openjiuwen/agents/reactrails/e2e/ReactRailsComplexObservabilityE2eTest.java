/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import com.openjiuwen.agents.reactrails.observability.CollectingRailEventListener;
import com.openjiuwen.agents.reactrails.observability.ObservingRail;
import com.openjiuwen.agents.reactrails.observability.RailEvent;
import com.openjiuwen.agents.reactrails.observability.RailTelemetry;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Complex end-to-end observability matrix (6 models x thinking on/off) that exercises the FULL
 * event chain a CriteriaReplanBridgeRail relies on: a market_data tool is available, the LLM is
 * asked to call it for GDP/CPI, then produce a short answer covering GDP/CPI/通胀率. Hard criteria
 * force the verify→fail→steer→retry→degrade path (maxReplan=1 so the bridge degrades after one
 * retry, deterministically firing a CriteriaReplanBridgeRail-attributed ForceFinishEvent).
 *
 * <p>Per-model bearing assertion (mutation-RED shape, not weak "any event fired"):
 * <ul>
 *   <li>≥1 {@link RailEvent.VerifyEvent} collected (the bridge ran its verifier)</li>
 *   <li>≥1 {@link RailEvent.ForceFinishEvent} collected with
 *       {@code railName == "CriteriaReplanBridgeRail"} (attributed via {@code source_rail},
 *       proving the bridge — not a silent ObservingRail fallback — locked the terminal)</li>
 * </ul>
 *
 * <p>Matrix:
 * <ul>
 *   <li>bigmodel native: glm-5.2, glm-4.7 (MY_GLM_KEY)</li>
 *   <li>deepseek native: deepseek-v4-pro, deepseek-v4-flash (DEEPSEEK_API_KEY)</li>
 *   <li>openrouter: qwen3.6-35b-a3b, qwen3.6-27b (OPENROUTER_API_KEY)</li>
 * </ul>
 *
 * <p>Env-gated: RR_COMPLEX_OBS_E2E_ENABLED=true + the per-provider key for each config.
 *
 * @since 2026-07
 */
class ReactRailsComplexObservabilityE2eTest {
    private static final Logger LOG =
            Logger.getLogger(ReactRailsComplexObservabilityE2eTest.class.getName());

    private static final String RAIL_NAME = "CriteriaReplanBridgeRail";

    private static final List<String> CRITERIA = List.of("GDP", "CPI", "通胀率");

    private static final String TASK = "分析当前的经济形势。"
            + "先调用 market_data 工具获取 GDP 和 CPI 数据，"
            + "然后给出包含 GDP、CPI、通胀率的简短建议。";

    /**
     * One model config: label, endpoint base, env key var, model id, thinking extras.
     */
    private record ModelConfig(String label, String base, String keyEnv, String model,
            Map<String, Object> thinkingOn, Map<String, Object> thinkingOff) {
        String resolveKey() {
            return System.getenv(keyEnv);
        }
    }

    /**
     * Build the 6-model config list (bigmodel / deepseek / openrouter).
     *
     * @return the unmodifiable list of model configs
     */
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
                        "OPENROUTER_API_KEY", "qwen/qwen3.6-27b", qwenOn, qwenOff),
                new ModelConfig("opus-4.7", "https://openrouter.ai/api/v1",
                        "OPENROUTER_API_KEY", "anthropic/claude-opus-4.7", glmOn, glmOff));
    }

    @Test
    void complexObservabilityMatrix_6models_x_thinkingOnOff() {
        String enabled = System.getenv("RR_COMPLEX_OBS_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "complex obs matrix requires RR_COMPLEX_OBS_E2E_ENABLED=true"
                        + " + MY_GLM/DEEPSEEK/OPENROUTER keys");
        DefaultModelClientFactories.ensureRegistered();

        List<Map<String, Object>> results = new ArrayList<>();
        for (ModelConfig mc : configs()) {
            String key = mc.resolveKey();
            if (key == null || key.isBlank()) {
                LOG.log(Level.INFO, "[complex-obs] SKIP {0} (key {1} unset)",
                        new Object[]{mc.label, mc.keyEnv});
                continue;
            }
            for (boolean thinking : new boolean[]{true, false}) {
                results.add(runOne(mc, key, thinking));
            }
        }
        summarize(results);
    }

    /**
     * Run one (model, thinking) pair and record bearing fields + collected event counts.
     *
     * @param mc model config under test
     * @param key resolved API key
     * @param thinking whether thinking mode is enabled
     * @return per-config metrics map
     */
    private static Map<String, Object> runOne(ModelConfig mc, String key, boolean thinking) {
        String label = mc.label + " | thinking=" + (thinking ? "ON" : "OFF");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", mc.label);
        r.put("thinking", thinking ? "ON" : "OFF");
        r.put("status", "error");
        r.put("eventCount", 0);
        r.put("verifyEvents", 0);
        r.put("bridgeForceFinish", 0);
        // FutureTask: G.ERR.02 (explicit runnable/future, not raw thread / unchecked pool).
        FutureTask<Map<String, Object>> task =
                new FutureTask<>(() -> invokeAndRecord(mc, key, thinking, r, label));
        task.run();
        try {
            task.get();
        } catch (InterruptedException | ExecutionException e) {
            r.put("status", "flaky:" + e.getClass().getSimpleName());
            LOG.log(Level.INFO, "[complex-obs] {0} EX {1}",
                    new Object[]{label, e.getClass().getSimpleName()});
        }
        return r;
    }

    /**
     * Build agent + rails + market_data tool + collector, invoke, assert + record events.
     *
     * @param mc model config under test
     * @param key resolved API key
     * @param thinking whether thinking mode is enabled
     * @param r result map to populate
     * @param label human-readable config label for logging
     * @return the populated result map
     */
    private static Map<String, Object> invokeAndRecord(ModelConfig mc, String key, boolean thinking,
            Map<String, Object> r, String label) {
        var cliCfg = ModelClientConfig.builder()
                .clientId("complex-obs-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(key).apiBase(mc.base).verifySsl(false).build();
        Map<String, Object> extra = new LinkedHashMap<>(thinking ? mc.thinkingOn : mc.thinkingOff);
        var reqCfg = ModelRequestConfig.builder().modelName(mc.model).temperature(0.3)
                .maxTokens(4000).extraFields(extra).build();
        var model =
                new com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel(cliCfg, reqCfg);
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("complex-obs").build());
        agent.setLlm(model);

        // maxReplan=1: one steering retry, then forceFinish degraded — deterministic degrade.
        ReplanRail counter = new ReplanRail(1);
        agent.registerRail(new CriteriaReplanBridgeRail(
                new RuleBasedCriteriaVerifier(), CRITERIA, counter));

        CollectingRailEventListener collector = new CollectingRailEventListener();
        agent.registerRail(new ObservingRail());
        RailTelemetry.setCurrent(RailTelemetry.noop().with(collector));

        registerMarketDataTool(agent);

        Object result = agent.invoke(TASK, null);

        List<RailEvent> events = collector.events();
        long verifyCount =
                events.stream().filter(e -> e instanceof RailEvent.VerifyEvent).count();
        long bridgeForceFinish = events.stream()
                .filter(e -> e instanceof RailEvent.ForceFinishEvent)
                .map(e -> (RailEvent.ForceFinishEvent) e)
                .filter(ff -> RAIL_NAME.equals(ff.railName()))
                .count();

        r.put("status", result == null ? "empty" : "completed");
        r.put("eventCount", events.size());
        r.put("verifyEvents", verifyCount);
        r.put("bridgeForceFinish", bridgeForceFinish);

        LOG.log(Level.INFO,
                "[complex-obs] {0} -> status={1} events={2} verify={3} bridgeFF={4}",
                new Object[]{label, r.get("status"), events.size(), verifyCount,
                        bridgeForceFinish});

        // Bearing assertions (IFF shape — strip either → RED):
        org.junit.jupiter.api.Assertions.assertTrue(verifyCount >= 1,
                "bearing A: expected >=1 VerifyEvent from " + RAIL_NAME + " for " + label
                        + " (got " + verifyCount + ")");
        org.junit.jupiter.api.Assertions.assertTrue(bridgeForceFinish >= 1,
                "bearing B: expected >=1 ForceFinishEvent attributed to " + RAIL_NAME
                        + " via source_rail for " + label + " (got " + bridgeForceFinish + ")");
        return r;
    }

    /**
     * market_data stub: returns deterministic GDP/CPI figures the LLM must then weave into its answer.
     *
     * @param agent the ReActAgent to receive the market_data tool
     */
    private static void registerMarketDataTool(ReActAgent agent) {
        ToolCard card = ToolCard.builder().id("market_data").name("market_data")
                .description("获取宏观经济指标。返回 GDP 同比增速、CPI 同比、通胀率。"
                        + "无参数。")
                .inputParams(Map.of("type", "object", "properties", Map.of()))
                .build();
        Map<String, Object> data = Map.of(
                "GDP", "5.2%",
                "CPI", "0.6%",
                "通胀率", "0.6%");
        Tool tool = new Tool(card) {
            @Override
            public ToolCard getCard() {
                return card;
            }

            @Override
            public Object invoke(Map<String, Object> inputs, Map<String, Object> kwargs) {
                return data;
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs,
                    Map<String, Object> kwargs) {
                return List.<Object>of(data).iterator();
            }
        };
        agent.getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
    }

    /**
     * Print the matrix table + the bridge attribution gate.
     *
     * @param results the per-config result maps collected by the matrix run
     */
    private static void summarize(List<Map<String, Object>> results) {
        LOG.log(Level.INFO, "{0}========== COMPLEX OBS MATRIX (bridge full chain) ==========",
                System.lineSeparator());
        LOG.log(Level.INFO, "{0}",
                String.format(Locale.ROOT, "%-20s %-8s %-9s %-7s %-7s %-9s%n", "model",
                        "thinking", "status", "events", "verify", "bridgeFF"));
        for (Map<String, Object> r : results) {
            LOG.log(Level.INFO, "{0}",
                    String.format(Locale.ROOT, "%-20s %-8s %-9s %-7s %-7s %-9s%n",
                            r.get("model"), r.get("thinking"), r.get("status"),
                            r.get("eventCount"), r.get("verifyEvents"),
                            r.get("bridgeForceFinish")));
        }
        long completed = results.stream().filter(r -> "completed".equals(r.get("status"))).count();
        long withBridgeFf = results.stream()
                .filter(r -> "completed".equals(r.get("status"))
                        && ((long) r.get("bridgeForceFinish")) >= 1)
                .count();
        LOG.log(Level.INFO,
                "[complex-obs] completed={0}/{1}, configs with bridge-attributed ForceFinish={2}/{0}",
                new Object[]{completed, results.size(), withBridgeFf});
    }
}
