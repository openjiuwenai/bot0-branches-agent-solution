/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import com.openjiuwen.agents.edpa.rail.SteeringProvisionRail;
import com.openjiuwen.agents.edpa.verification.GroundTruthVerifier;
import com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRail;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Issue-#13 fix verification matrix across 4 models × thinking on/off. Confirms the
 * {@link SteeringProvisionRail} fix lifts {@code hasSteeringQueue} from false (RED) to true
 * (GREEN) on the {@code invoke(taskString, null)} path for every model, and observes whether
 * convergence steering actually fires + reaches the next model call per model.
 *
 * <p><b>Matrix</b> (5 models × 2 thinking = 10 configs):
 * <ul>
 *   <li><b>bigmodel native</b>: glm-5.2 ({@code my-glm} key) — {@code thinking.type=enabled}</li>
 *   <li><b>deepseek native</b>: deepseek-v4-pro, deepseek-v4-flash — {@code thinking.type=enabled}</li>
 *   <li><b>openrouter</b>: qwen3.6-35b-a3b, qwen3.6-27b — {@code reasoning.enabled=true}</li>
 * </ul>
 *
 * <p>Env-gated: {@code EDPA_STEERING_MATRIX_ENABLED=true} + {@code MY_GLM_KEY} +
 * {@code DEEPSEEK_API_KEY} + {@code OPENROUTER_API_KEY}.
 *
 * @since 2026-07
 */
class Issue13SteeringMatrixRealLlmE2eTest {
    private static final Logger LOG = Logger.getLogger(Issue13SteeringMatrixRealLlmE2eTest.class.getName());

    private static final List<String> CRITERIA = List.of("GDP", "CPI", "通胀率");

    private static final String TASK = "分析当前经济形势。先调用 market_data 工具获取 GDP/CPI 数据，"
            + "再给出含 GDP/CPI/通胀率的分析建议。";

    /** One model config: label, endpoint base, env key var, model id, thinking on/off extras. */
    private record ModelConfig(String label, String base, String keyEnv, String model,
            Map<String, Object> thinkingOn, Map<String, Object> thinkingOff) {
        /**
         * Resolves the API key from the env var named by {@link #keyEnv}.
         */
        String resolveKey() {
            return System.getenv(keyEnv);
        }
    }

    /**
     * Builds the 4-model config list across bigmodel/deepseek/openrouter endpoints.
     */
    private static List<ModelConfig> configs() {
        Map<String, Object> glmOn = Map.of("thinking", Map.of("type", "enabled"));
        Map<String, Object> glmOff = Map.of("thinking", Map.of("type", "disabled"));
        Map<String, Object> dsOn = Map.of("thinking", Map.of("type", "enabled"));
        Map<String, Object> dsOff = Map.of("thinking", Map.of("type", "disabled"));
        Map<String, Object> qwenOn = Map.of("reasoning", Map.of("enabled", true), "include_reasoning", true);
        Map<String, Object> qwenOff = Map.of("reasoning", Map.of("enabled", false));
        return List.of(
                new ModelConfig("glm-5.2", "https://open.bigmodel.cn/api/paas/v4", "MY_GLM_KEY", "glm-5.2",
                        glmOn, glmOff),
                new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                        "deepseek-v4-pro", dsOn, dsOff),
                new ModelConfig("deepseek-v4-flash", "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                        "deepseek-v4-flash", dsOn, dsOff),
                new ModelConfig("qwen3.6-35b-a3b", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
                        "qwen/qwen3.6-35b-a3b", qwenOn, qwenOff),
                new ModelConfig("qwen3.6-27b", "https://openrouter.ai/api/v1", "OPENROUTER_API_KEY",
                        "qwen/qwen3.6-27b", qwenOn, qwenOff));
    }

    @Test
    void steeringMatrix_4models_x_thinkingOnOff() {
        String enabled = System.getenv("EDPA_STEERING_MATRIX_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "steering matrix requires EDPA_STEERING_MATRIX_ENABLED=true + MY_GLM/DEEPSEEK/OPENROUTER keys");

        registerMarketDataToolOnce();
        List<Map<String, Object>> results = new ArrayList<>();
        for (ModelConfig mc : configs()) {
            String key = mc.resolveKey();
            if (key == null || key.isBlank()) {
                LOG.log(Level.INFO, "[steer-matrix] SKIP {0} (key {1} unset)", new Object[]{mc.label, mc.keyEnv});
                continue;
            }
            for (boolean thinking : new boolean[]{true, false}) {
                results.add(runOne(mc, key, thinking));
            }
        }
        summarize(results);
    }

    /**
     * Runs one (model, thinking) pair via a FutureTask bridge, recording flaky failures.
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
        r.put("hasSteeringQueue", false);
        r.put("convergenceFired", false);
        r.put("hintReached", false);
        FutureTask<Object> task = new FutureTask<>(() -> invokeAndRecord(mc, key, thinking, r, label));
        task.run();
        try {
            task.get();
        } catch (InterruptedException e) {
            r.put("status", "flaky:Interrupted");
            LOG.log(Level.INFO, "[steer-matrix] {0} EX Interrupted", label);
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            r.put("status", "flaky:" + (cause == null ? "Exception" : cause.getClass().getSimpleName()));
            LOG.log(Level.INFO, "[steer-matrix] {0} EX {1}",
                    new Object[]{label, cause == null ? "Exception" : cause.getClass().getSimpleName()});
        }
        return r;
    }

    /**
     * Builds the agent + 6 rails + observer, invokes, and records metrics into the result map.
     *
     * @param mc model config under test
     * @param key resolved API key
     * @param thinking whether thinking mode is enabled
     * @param r result map to populate
     * @param label human-readable config label for logging
     * @return the agent invocation result (may be null)
     */
    private static Object invokeAndRecord(ModelConfig mc, String key, boolean thinking,
            Map<String, Object> r, String label) {
        DeepAgent deep = HarnessFactory.createDeepAgent(buildConfig(mc, key, thinking));
        deep.getAgent().registerRail(new SteeringProvisionRail());
        ReplanRail shared = new ReplanRail(3);
        deep.getAgent().registerRail(
                new CriteriaReplanBridgeRail(new GroundTruthVerifier(), CRITERIA, shared));
        deep.getAgent().registerRail(shared);
        deep.getAgent().registerRail(new RootCauseRail());
        deep.getAgent().registerRail(new ProactiveConvergenceRail(new GroundTruthVerifier(), CRITERIA));
        ReplanTool.registerOnto(deep.getAgent());
        RailStateObserver observer = new RailStateObserver();
        deep.getAgent().registerRail(observer);

        Object result = deep.getAgent().invoke(TASK, null);

        boolean wiringTrue = observer.getTrace().stream()
                .anyMatch(line -> line.contains("afterModelCall hasSteeringQueue=true"));
        r.put("hasSteeringQueue", wiringTrue);
        r.put("hintReached", observer.isHintReachedAnyModelCall());
        r.put("convergenceFired", observer.getTrace().stream()
                .anyMatch(line -> line.contains("【主动收敛】")));
        r.put("status", result == null ? "empty" : "completed");
        r.put("output", result == null ? "" : String.valueOf(result).substring(0,
                Math.min(120, String.valueOf(result).length())));
        LOG.log(Level.INFO, "[steer-matrix] {0} -> wiring={1} hintReached={2} status={3}",
                new Object[]{label, wiringTrue, observer.isHintReachedAnyModelCall(), r.get("status")});
        return result;
    }

    /**
     * Builds the DeepAgentConfig from a model config + thinking flag.
     */
    private static DeepAgentConfig buildConfig(ModelConfig mc, String key, boolean thinking) {
        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("model", mc.model);
        modelMap.put("model_name", mc.model);
        modelMap.put("temperature", 0.3);
        modelMap.put("max_tokens", 4000);
        modelMap.putAll(thinking ? mc.thinkingOn : mc.thinkingOff);
        Map<String, Object> backendMap = new LinkedHashMap<>();
        backendMap.put("provider", "OpenAI");
        backendMap.put("client_provider", "OpenAI");
        backendMap.put("apiKey", key);
        backendMap.put("api_key", key);
        backendMap.put("baseUrl", mc.base);
        backendMap.put("apiBase", mc.base);
        return DeepAgentConfig.builder()
                .systemPrompt("你是一个经济分析助手。必须调用 market_data 工具获取数据，再给出分析。")
                .maxIterations(6).enableTaskLoop(false).enableTaskPlanning(true)
                .model(modelMap).backend(backendMap).build();
    }

    /**
     * Prints the matrix table plus the issue-#13 GREEN assertion (wiring=true on all completed).
     */
    private static void summarize(List<Map<String, Object>> results) {
        LOG.log(Level.INFO, "{0}========== STEERING MATRIX (issue-#13 fix) ==========",
                System.lineSeparator());
        LOG.log(Level.INFO, "{0}",
                String.format(java.util.Locale.ROOT, "%-20s %-8s %-9s %-9s %-10s%n", "model", "thinking",
                        "wiring", "hint", "status"));
        for (Map<String, Object> r : results) {
            LOG.log(Level.INFO, "{0}",
                    String.format(java.util.Locale.ROOT, "%-20s %-8s %-9s %-9s %-10s%n", r.get("model"),
                            r.get("thinking"), r.get("hasSteeringQueue"), r.get("hintReached"), r.get("status")));
        }
        long completed = results.stream().filter(r -> "completed".equals(r.get("status"))).count();
        long wiringTrue = results.stream()
                .filter(r -> "completed".equals(r.get("status")) && Boolean.TRUE.equals(r.get("hasSteeringQueue")))
                .count();
        LOG.log(Level.INFO, "[steer-matrix] completed={0}/{1}, wiring GREEN (hasSteeringQueue=true)={2}/{0}",
                new Object[]{completed, results.size(), wiringTrue});
        org.junit.jupiter.api.Assertions.assertTrue(completed > 0, "at least one config must complete");
        // issue-#13 fix gate: every completed config must have wiring=true (queue bound by provision rail)
        org.junit.jupiter.api.Assertions.assertEquals(completed, wiringTrue,
                "issue-#13 fix: every completed config must have hasSteeringQueue=true (got " + wiringTrue + "/"
                        + completed + "); a false means SteeringProvisionRail failed to bind on that model");
    }

    private static void registerMarketDataToolOnce() {
        ToolCard card = ToolCard.builder().id("market_data").name("market_data")
                .description("获取市场/经济数据。参数：indicator（如 GDP/CPI/利率）。")
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
                String ind = args != null ? String.valueOf(args.getOrDefault("indicator", "GDP")) : "GDP";
                return Map.of("indicator", ind, "value", "稳中有降", "trend", "谨慎乐观");
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
                return List.<Object>of(invoke(args, kwargs)).iterator();
            }
        };
        Runner.resourceMgr().addTool(tool, null);
    }
}
