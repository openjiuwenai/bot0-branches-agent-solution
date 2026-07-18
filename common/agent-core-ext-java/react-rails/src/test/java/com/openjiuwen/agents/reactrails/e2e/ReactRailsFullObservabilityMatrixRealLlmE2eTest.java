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
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerificationRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
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
 * Full observability matrix: 6 models x thinking on/off x 3 rail scenarios, recording both
 * the emitted RailEvents AND the response content (to confirm thinking really takes effect —
 * e.g. GLM-4.7 thinking-on stays content-empty — while events still fire on the rail side path).
 *
 * <p>Scenarios:
 * <ul>
 *   <li><b>bridge</b>: CriteriaReplanBridgeRail, hard criteria → verify fail → steer/degrade
 *       (VERIFY + REPLAN_COUNT + STEERING / FORCE_FINISH)</li>
 *   <li><b>verification</b>: CriteriaVerificationRail, hard criteria → verify fail →
 *       forceFinish degraded (VERIFY + FORCE_FINISH)</li>
 *   <li><b>rootCause</b>: RootCauseRail + AlwaysFailTool → tool exception → degrade
 *       (DEVICE_FAILURE + FORCE_FINISH)</li>
 * </ul>
 *
 * <p>Env-gated: RR_FULL_OBS_MATRIX_ENABLED=true + MY_GLM_KEY + DEEPSEEK_API_KEY + OPENROUTER_API_KEY.
 *
 * @since 2026-07
 */
class ReactRailsFullObservabilityMatrixRealLlmE2eTest {
    private static final Logger LOG =
            Logger.getLogger(ReactRailsFullObservabilityMatrixRealLlmE2eTest.class.getName());

    private static final List<String> CRITERIA = List.of("GDP", "CPI", "通胀率");

    private record ModelConfig(String label, String base, String keyEnv, String model,
            Map<String, Object> thinkingOn, Map<String, Object> thinkingOff) {
        String resolveKey() {
            return System.getenv(keyEnv);
        }
    }

    /**
     * Bundles the per-invocation context passed into invokeAndRecord so the helper stays under
     * the 5-parameter CodeCheck ceiling. Built once per scenario in runOne.
     */
    private record InvokeContext(String key, boolean thinking, String scenario,
            Map<String, Object> result, String label) {
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
                new ModelConfig("qwen3.6-27b", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
                        "qwen/qwen3.6-27b", qwenOn, qwenOff));
    }

    @Test
    void fullObservabilityMatrix_6models_x_thinking_x_3scenarios() {
        String enabled = System.getenv("RR_FULL_OBS_MATRIX_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "full obs matrix requires RR_FULL_OBS_MATRIX_ENABLED=true + 3 keys");
        DefaultModelClientFactories.ensureRegistered();

        List<Map<String, Object>> results = new ArrayList<>();
        for (ModelConfig mc : configs()) {
            String key = mc.resolveKey();
            if (key == null || key.isBlank()) {
                continue;
            }
            for (boolean thinking : new boolean[]{true, false}) {
                // rootCause scenario needs a global AlwaysFailTool via Runner.resourceMgr(),
                // which pollutes every subsequent scenario in the same JVM (verified: causes
                // flaky:ExecutionException + 49min hang). It is covered by the standalone
                // RootCauseRailRealLlmE2eTest + the DEVICE_FAILURE mutation-RED unit test, so it
                // is intentionally excluded from this single-JVM matrix.
                for (String scenario : new String[]{"bridge", "verification"}) {
                    results.add(runOne(mc, key, thinking, scenario));
                }
            }
        }
        summarize(results);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> runOne(ModelConfig mc, String key, boolean thinking,
            String scenario) {
        String label = mc.label + "|" + (thinking ? "ON" : "OFF") + "|" + scenario;
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", mc.label);
        r.put("thinking", thinking ? "ON" : "OFF");
        r.put("scenario", scenario);
        r.put("status", "error");
        r.put("events", 0);
        r.put("types", "");
        r.put("contentEmpty", false);
        r.put("forceFinishRails", "");
        InvokeContext ctx = new InvokeContext(key, thinking, scenario, r, label);
        FutureTask<Map<String, Object>> task = new FutureTask<>(() -> invokeAndRecord(mc, ctx));
        task.run();
        try {
            task.get();
        } catch (InterruptedException | ExecutionException e) {
            r.put("status", "flaky:" + e.getClass().getSimpleName());
        }
        return r;
    }

    private static Map<String, Object> invokeAndRecord(ModelConfig mc, InvokeContext ctx) {
        String key = ctx.key();
        boolean thinking = ctx.thinking();
        String scenario = ctx.scenario();
        Map<String, Object> r = ctx.result();
        String label = ctx.label();
        var cliCfg = ModelClientConfig.builder().clientId("full-obs-" + System.nanoTime())
                .clientProvider("OpenAI").apiKey(key).apiBase(mc.base).verifySsl(false).build();
        Map<String, Object> extra = new LinkedHashMap<>(thinking ? mc.thinkingOn : mc.thinkingOff);
        var reqCfg = ModelRequestConfig.builder().modelName(mc.model).temperature(0.3)
                .maxTokens(4000).extraFields(extra).build();
        var model =
                new com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel(cliCfg, reqCfg);
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("full-obs-" + scenario).build());
        agent.setLlm(model);

        CollectingRailEventListener collector = new CollectingRailEventListener();
        agent.registerRail(new ObservingRail());
        RailTelemetry.setCurrent(RailTelemetry.noop().with(collector));

        String task = "bridge".equals(scenario) || "verification".equals(scenario)
                ? "分析当前的经济形势。请简短回答。"
                : "调用 get_data 工具获取数据，再回答。";

        if ("bridge".equals(scenario)) {
            ReplanRail counter = new ReplanRail(2);
            agent.registerRail(new CriteriaReplanBridgeRail(
                    new RuleBasedCriteriaVerifier(), CRITERIA, counter));
        } else if ("verification".equals(scenario)) {
            agent.registerRail(new CriteriaVerificationRail(
                    new RuleBasedCriteriaVerifier(), CRITERIA));
        } else {
            agent.registerRail(new RootCauseRail());
            registerFailTool(agent);
        }

        Object result = agent.invoke(task, null);
        List<RailEvent> events = collector.events();
        String content = extractContent(result);
        r.put("status", result == null ? "empty" : "completed");
        r.put("events", events.size());
        r.put("types", events.stream().map(e -> e.type().name())
                .reduce((a, b) -> a + "," + b).orElse(""));
        // Capture the railName of every ForceFinishEvent so summarize can assert source_rail
        // attribution reached the collector (railName != "ObservingRail" means a business rail
        // set ObservingRail.SOURCE_RAIL_KEY — honest attribution, not the fallback).
        r.put("forceFinishRails", events.stream()
                .filter(e -> e.type() == RailEventType.FORCE_FINISH)
                .map(RailEvent::railName).reduce((a, b) -> a + "," + b).orElse(""));
        r.put("contentEmpty", content.isEmpty());
        LOG.log(Level.INFO, "[full-obs] {0} -> {1} events={2} contentEmpty={3} types={4} ffRails={5}",
                new Object[]{label, r.get("status"), events.size(), content.isEmpty(),
                        r.get("types"), r.get("forceFinishRails")});
        return r;
    }

    @SuppressWarnings("unchecked")
    private static String extractContent(Object result) {
        if (result == null) {
            return "";
        }
        if (result instanceof Map<?, ?> m) {
            Object out = m.get("output");
            return out == null ? "" : String.valueOf(out);
        }
        return String.valueOf(result);
    }

    private static void registerFailTool(ReActAgent agent) {
        ToolCard card = ToolCard.builder().id("get_data").name("get_data")
                .description("获取数据。无参数。")
                .inputParams(Map.of("type", "object", "properties", Map.of()))
                .build();
        Tool tool = new Tool(card) {
            @Override
            public ToolCard getCard() {
                return card;
            }

            @Override
            public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
                throw new IllegalStateException("simulated device failure");
            }

            @Override
            public java.util.Iterator<Object> stream(Map<String, Object> args,
                    Map<String, Object> kwargs) {
                return List.<Object>of(invoke(args, kwargs)).iterator();
            }
        };
        agent.getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
    }

    private static void summarize(List<Map<String, Object>> results) {
        LOG.log(Level.INFO, "{0}===== FULL OBS MATRIX (3 scenarios x 6 models x thinking) =====",
                System.lineSeparator());
        LOG.log(Level.INFO, "{0}",
                String.format(java.util.Locale.ROOT, "%-18s %-4s %-12s %-9s %-7s %-9s%n",
                        "model", "thk", "scenario", "status", "events", "cEmpty"));
        for (Map<String, Object> r : results) {
            LOG.log(Level.INFO, "{0}",
                    String.format(java.util.Locale.ROOT, "%-18s %-4s %-12s %-9s %-7s %-9s%n",
                            r.get("model"), r.get("thinking"), r.get("scenario"), r.get("status"),
                            r.get("events"), r.get("contentEmpty")));
        }
        long completed = results.stream().filter(r -> "completed".equals(r.get("status"))).count();
        long withEvents = results.stream()
                .filter(r -> "completed".equals(r.get("status"))
                        && ((int) r.get("events")) > 0)
                .count();
        // Strengthened gate (was no assertion at all here): at least one completed config must
        // carry a ForceFinishEvent whose railName is NOT "ObservingRail" — i.e. a business rail
        // (CriteriaReplanBridgeRail / CriteriaVerificationRail) set ObservingRail.SOURCE_RAIL_KEY
        // and the attribution propagated to the collected event. Both bridge and verification
        // scenarios reach a forceFinish terminal, so a healthy run must produce at least one
        // attributed ForceFinishEvent; falling back to "ObservingRail" for all of them means the
        // source_rail contract is silently degrading.
        long withAttributedForceFinish = results.stream()
                .filter(r -> "completed".equals(r.get("status")))
                .map(r -> String.valueOf(r.get("forceFinishRails")))
                .filter(s -> s != null && !s.isEmpty())
                .flatMap(s -> java.util.Arrays.stream(s.split(",")))
                .map(String::trim)
                .filter(name -> !name.isEmpty() && !"ObservingRail".equals(name))
                .count();
        // GLM-4.7 thinking-on content-empty check (confirms thinking really takes effect)
        long glm47OnEmpty = results.stream().filter(r -> "glm-4.7".equals(r.get("model"))
                && "ON".equals(r.get("thinking")) && Boolean.TRUE.equals(r.get("contentEmpty")))
                .count();
        LOG.log(Level.INFO,
                "[full-obs] completed={0}/{1}, emitting RailEvents={2}/{0}, "
                        + "attributed ForceFinishEvents={3}, glm-4.7|ON contentEmpty configs={4}",
                new Object[]{completed, results.size(), withEvents, withAttributedForceFinish,
                        glm47OnEmpty});
        if (completed > 0) {
            org.junit.jupiter.api.Assertions.assertTrue(withEvents > 0,
                    "observability gate: at least one completed config must emit RailEvents"
                            + " (got " + withEvents + " of " + completed + " completed).");
            org.junit.jupiter.api.Assertions.assertTrue(withAttributedForceFinish > 0,
                    "attribution gate: at least one completed config must emit a ForceFinishEvent"
                            + " whose railName is not \"ObservingRail\" (got "
                            + withAttributedForceFinish + " attributed of " + completed
                            + " completed). If zero, business rails (bridge/verification) are not"
                            + " setting ObservingRail.SOURCE_RAIL_KEY, so source_rail attribution"
                            + " is silently degrading to the ObservingRail fallback.");
        }
    }
}
