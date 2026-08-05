/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import com.openjiuwen.agents.reactrails.observability.CollectingRailEventListener;
import com.openjiuwen.agents.reactrails.observability.ObservingRail;
import com.openjiuwen.agents.reactrails.observability.RailEvent;
import com.openjiuwen.agents.reactrails.observability.RailEventType;
import com.openjiuwen.agents.reactrails.observability.RailTelemetry;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Real-LLM matrix (6 models x thinking on/off) that proves the observability SPI actually
 * surfaces rail state transitions. Each config runs a CriteriaReplanBridgeRail with hard
 * criteria (GDP/CPI/通胀率) that an LLM rarely satisfies in a short answer, so verify→fail→
 * steer/degrade fires — and a CollectingRailEventListener asserts real RailEvents arrived
 * (the pre-issue-#15 silent state would collect zero).
 *
 * <p>Matrix:
 * <ul>
 *   <li>bigmodel native: glm-5.2, glm-4.7 (MY_GLM_KEY)</li>
 *   <li>deepseek native: deepseek-v4-pro, deepseek-v4-flash (DEEPSEEK_API_KEY)</li>
 *   <li>openrouter: qwen3.6-35b-a3b, qwen3.6-27b (OPENROUTER_API_KEY)</li>
 * </ul>
 *
 * <p>Env-gated: RR_OBS_MATRIX_ENABLED=true + MY_GLM_KEY + DEEPSEEK_API_KEY + OPENROUTER_API_KEY.
 *
 * @since 2026-07
 */
class ReactRailsObservabilityMatrixRealLlmE2eTest {
    private static final Logger LOG =
            Logger.getLogger(ReactRailsObservabilityMatrixRealLlmE2eTest.class.getName());

    private static final List<String> CRITERIA = List.of("GDP", "CPI", "通胀率");

    private static final String TASK = "分析当前的经济形势。请简短回答。";

    /** One model config: label, endpoint base, env key var, model id, thinking extras. */
    private record ModelConfig(String label, String base, String keyEnv, String model,
            Map<String, Object> thinkingOn, Map<String, Object> thinkingOff) {
        String resolveKey() {
            return System.getenv(keyEnv);
        }
    }

    /**
     * Build the 6-model config list.
     *
     * @return the unmodifiable list of model configs for the matrix
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
                new ModelConfig("deepseek-v4-flash", "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                        "deepseek-v4-flash", glmOn, glmOff),
                new ModelConfig("qwen3.6-35b-a3b", "https://openrouter.ai/api/v1",
                        "OPENROUTER_API_KEY", "qwen/qwen3.6-35b-a3b", qwenOn, qwenOff),
                new ModelConfig("qwen3.6-27b", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
                        "qwen/qwen3.6-27b", qwenOn, qwenOff));
    }

    @Test
    void observabilityMatrix_6models_x_thinkingOnOff() {
        String enabled = System.getenv("RR_OBS_MATRIX_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "observability matrix requires RR_OBS_MATRIX_ENABLED=true"
                        + " + MY_GLM/DEEPSEEK/OPENROUTER keys");
        DefaultModelClientFactories.ensureRegistered();

        List<Map<String, Object>> results = new ArrayList<>();
        for (ModelConfig mc : configs()) {
            String key = mc.resolveKey();
            if (key == null || key.isBlank()) {
                LOG.log(Level.INFO, "[obs-matrix] SKIP {0} (key {1} unset)",
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
     * Run one (model, thinking) pair with a CollectingRailEventListener and record events.
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
        r.put("eventTypes", "");
        r.put("forceFinishRails", "");
        FutureTask<Map<String, Object>> task =
                new FutureTask<>(() -> invokeAndRecord(mc, key, thinking, r, label));
        task.run();
        try {
            task.get();
        } catch (InterruptedException | ExecutionException e) {
            r.put("status", "flaky:" + e.getClass().getSimpleName());
            LOG.log(Level.INFO, "[obs-matrix] {0} EX {1}", new Object[]{label, e.getClass().getSimpleName()});
        }
        return r;
    }

    /**
     * Build agent + rail + collector, invoke, record events into the result map.
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
                .clientId("obs-matrix-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(key).apiBase(mc.base).verifySsl(false).build();
        Map<String, Object> extra = new LinkedHashMap<>(thinking ? mc.thinkingOn : mc.thinkingOff);
        var reqCfg = ModelRequestConfig.builder().modelName(mc.model).temperature(0.3)
                .maxTokens(4000).extraFields(extra).build();
        var model =
                new com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel(cliCfg, reqCfg);
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("obs-matrix").build());
        agent.setLlm(model);

        ReplanRail counter = new ReplanRail(2);
        agent.registerRail(new CriteriaReplanBridgeRail(
                new RuleBasedCriteriaVerifier(), CRITERIA, counter));

        CollectingRailEventListener collector = new CollectingRailEventListener();
        agent.registerRail(new ObservingRail());
        RailTelemetry.setCurrent(RailTelemetry.noop().with(collector));

        Object result = agent.invoke(TASK, null);

        List<RailEvent> events = collector.events();
        r.put("status", result == null ? "empty" : "completed");
        r.put("eventCount", events.size());
        r.put("eventTypes", events.stream().map(e -> e.type().name())
                .reduce((a, b) -> a + "," + b).orElse(""));
        // Capture the railName of every ForceFinishEvent so summarize can assert source_rail
        // attribution reached the collector (railName != "ObservingRail" means a business rail
        // set ObservingRail.SOURCE_RAIL_KEY — the honest-attribution contract, not just any fire).
        r.put("forceFinishRails", events.stream()
                .filter(e -> e.type() == RailEventType.FORCE_FINISH)
                .map(RailEvent::railName).reduce((a, b) -> a + "," + b).orElse(""));
        LOG.log(Level.INFO,
                "[obs-matrix] {0} -> status={1} events={2} types={3} ffRails={4}",
                new Object[]{label, r.get("status"), events.size(), r.get("eventTypes"),
                        r.get("forceFinishRails")});
        return r;
    }

    /**
     * Print the matrix table + the observability gate (every completed config emitted events).
     *
     * @param results the per-config result maps collected by the matrix run
     */
    private static void summarize(List<Map<String, Object>> results) {
        LOG.log(Level.INFO, "{0}========== OBSERVABILITY MATRIX (issue #15) ==========",
                System.lineSeparator());
        LOG.log(Level.INFO, "{0}",
                String.format(java.util.Locale.ROOT, "%-20s %-8s %-9s %-8s%n", "model",
                        "thinking", "status", "events"));
        for (Map<String, Object> r : results) {
            LOG.log(Level.INFO, "{0}",
                    String.format(java.util.Locale.ROOT, "%-20s %-8s %-9s %-8s%n", r.get("model"),
                            r.get("thinking"), r.get("status"), r.get("eventCount")));
        }
        long completed = results.stream().filter(r -> "completed".equals(r.get("status"))).count();
        long withEvents = results.stream()
                .filter(r -> "completed".equals(r.get("status"))
                        && ((int) r.get("eventCount")) > 0)
                .count();
        // Strengthened gate (was withEvents>0): at least one completed config must have a
        // ForceFinishEvent whose railName is NOT "ObservingRail" — i.e. a business rail set
        // ObservingRail.SOURCE_RAIL_KEY and the attribution propagated to the collected event.
        // A bare withEvents>0 would pass even if every ForceFinishEvent fell back to the
        // "ObservingRail" default (rail forgot to set source_rail) — which is exactly the silent
        // attribution gap this gate is meant to catch.
        long withAttributedForceFinish = results.stream()
                .filter(r -> "completed".equals(r.get("status")))
                .map(r -> String.valueOf(r.get("forceFinishRails")))
                .filter(s -> s != null && !s.isEmpty())
                .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(name -> !name.isEmpty() && !"ObservingRail".equals(name))
                .count();
        LOG.log(Level.INFO,
                "[obs-matrix] completed={0}/{1}, configs emitting RailEvents={2}/{0}, "
                        + "attributed ForceFinishEvents={3}",
                new Object[]{completed, results.size(), withEvents, withAttributedForceFinish});
        if (completed > 0) {
            org.junit.jupiter.api.Assertions.assertTrue(withEvents > 0,
                    "observability gate: at least one completed config must emit RailEvents"
                            + " (got " + withEvents + " of " + completed
                            + " completed). If zero, the SPI is not surfacing transitions.");
            org.junit.jupiter.api.Assertions.assertTrue(withAttributedForceFinish > 0,
                    "attribution gate: at least one completed config must emit a ForceFinishEvent"
                            + " whose railName is not \"ObservingRail\" (got "
                            + withAttributedForceFinish + " attributed of " + completed
                            + " completed). If zero, business rails are not setting"
                            + " ObservingRail.SOURCE_RAIL_KEY, so source_rail attribution is"
                            + " silently degrading to the ObservingRail fallback.");
        }
    }
}
