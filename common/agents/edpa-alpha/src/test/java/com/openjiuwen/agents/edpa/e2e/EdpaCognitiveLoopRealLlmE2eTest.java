/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import com.openjiuwen.agents.edpa.verification.GroundTruthVerifier;
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

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase 1 bearing e2e: cognitive loop on DeepAgent — 4 rails fire (GEPA 核心假设).
 *
 * <p>v4 验证了 ReplanRail/ReplanTool 真 fire（deep.getAgent().invoke 正确路径）。
 * v5 加 market_data 工具 + 硬 criteria（GDP/CPI/通胀率，LLM 难满足）→
 * CriteriaReplanBridgeRail verify fail → replan（认知闭环 Plan→Exec→Verify→Replan）。
 *
 * <p>Wiring: HarnessFactory.createDeepAgent(config) → deep.getAgent().registerRail(4 cognitive rails)
 * + market_data tool → deep.getAgent().invoke(task, null) → railedModelCall loop + rail fire.
 *
 * <p>Env-gated: OPENJIUWEN_API_KEY/BASE_URL/MODEL + EDPA_COGNITIVE_E2E_ENABLED=true.
 *
 * @since 2026-07
 */
class EdpaCognitiveLoopRealLlmE2eTest {
    private static final Logger LOG = Logger.getLogger(EdpaCognitiveLoopRealLlmE2eTest.class.getName());

    @Test
    void cognitiveRailsFireOnDeepAgentRealLlm() {
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        String model = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "glm-4.7");
        String enabled = System.getenv("EDPA_COGNITIVE_E2E_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                key != null && !key.isBlank() && base != null && !base.isBlank() && "true".equalsIgnoreCase(enabled),
                "cognitive-loop e2e requires OPENJIUWEN_API_KEY/BASE_URL + EDPA_COGNITIVE_E2E_ENABLED=true");

        Map<String, Object> modelMap = new LinkedHashMap<>();
        modelMap.put("model", model);
        modelMap.put("model_name", model);
        modelMap.put("temperature", 0.3);
        modelMap.put("max_tokens", 4000);
        // thinking control (from env LLM_THINKING, same modes as pev LlmClient)
        String mode = System.getenv().getOrDefault("LLM_THINKING", "glm-off");
        switch (mode) {
            case "qwen-off" -> modelMap.put("enable_thinking", false);
            case "qwen-on" -> modelMap.put("enable_thinking", true);
            case "thinking-on" -> modelMap.put("thinking", Map.of("type", "enabled"));
            case "thinking-off" -> modelMap.put("thinking", Map.of("type", "disabled"));
            default -> modelMap.put("thinking", Map.of("type", "disabled"));
        }
        Map<String, Object> backendMap = new LinkedHashMap<>();
        backendMap.put("provider", "OpenAI");
        backendMap.put("client_provider", "OpenAI");
        backendMap.put("apiKey", key);
        backendMap.put("api_key", key);
        backendMap.put("baseUrl", base);
        backendMap.put("apiBase", base);

        DeepAgentConfig config = DeepAgentConfig.builder().systemPrompt("你是一个经济分析助手。必须调用 market_data 工具获取数据，再给出分析。")
                .maxIterations(8).enableTaskLoop(false).enableTaskPlanning(true).model(modelMap).backend(backendMap)
                .build();

        DeepAgent deep = HarnessFactory.createDeepAgent(config);

        // 4 cognitive rails onto DeepAgent's inner ReActAgent
        ReplanRail sharedCounter = new ReplanRail(3);
        // 硬 criteria: GDP/CPI/通胀率 — LLM 分析难含这些精确词 → verify fail → replan
        deep.getAgent().registerRail(
                new CriteriaReplanBridgeRail(new GroundTruthVerifier(), List.of("GDP", "CPI", "通胀率"), sharedCounter));
        deep.getAgent().registerRail(sharedCounter);
        deep.getAgent().registerRail(new RootCauseRail());
        ReplanTool.registerOnto(deep.getAgent());

        // market_data 工具（让 LLM 有数据工具，不再只 __replan__）
        registerMarketDataTool(deep);

        LOG.log(Level.INFO, "[cognitive-e2e] rails registered, invoking...");
        Object result = deep.getAgent().invoke("分析当前经济形势。先调用 market_data 工具获取 GDP/CPI 数据，再给出含 GDP/CPI/通胀率的分析建议。", null);

        String out = String.valueOf(result);
        LOG.log(Level.INFO, "[cognitive-e2e] result={0}",
                out.substring(0, Math.min(400, out.length())));
        // 软观察：Criteria verify 是否触发（result 含 verified 痕迹 / replan 痕迹）
        boolean hasVerify = out.contains("verified") || out.contains("replan") || out.contains("GDP");
        LOG.log(Level.INFO, "[cognitive-e2e] hasVerifyTrace={0}", hasVerify);
        // wiring proof: 证认知 rail 挂载 DeepAgent + invoke 真 LLM 跑通
        // rail fire 行为（verify→replan / root-cause degrade / convergence flatline）靠 mock 单测承重，非此 e2e gate
        org.junit.jupiter.api.Assertions.assertNotNull(result, "DeepAgent invoke must return non-null");
    }

    private static void registerMarketDataTool(DeepAgent deep) {
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
        deep.getAgent().getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
    }
}
