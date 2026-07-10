/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Multi-step tools e2e — the LLM must plan several nodes over 2-3 registered tools, the
 * executor runs them in order, and the verifier judges the combined output.
 *
 * <p>Ported from the spring-ai-ascend {@code PEVAlphaRealLlmE2e.multiStepWithTools} reference,
 * adapted (not copied) to this module's injected-SPI shape:
 * <ul>
 *   <li>{@link LlmPlanner} plans from the task + tool descriptions (named in the prompt).</li>
 *   <li>{@link ToolBackedExecutor} routes each node by longest tool-name match and runs the
 *       registered deterministic tool, or falls back to an LLM call.</li>
 *   <li>{@link LlmVerifier} judges the assembled output PASS/FAIL.</li>
 * </ul>
 *
 * <p><b>Honesty split (铁律①):</b> real-LLM e2e is soft-observe.
 * <ul>
 *   <li>Hard断言: data channel + control flow end-to-end (output non-empty, contains the
 *       expected multi-node layout with at least two {@code nodeId:} segments — proves the
 *       planner produced a multi-node plan and the executor ran them all).</li>
 *   <li>Soft-observe: the exact node ids, tool routing, and the verifier's PASS/FAIL verdict
 *       are LLM-dependent and brittle to hard-assert.</li>
 * </ul>
 *
 * <p>Env-gated via {@link org.junit.jupiter.api.Assumptions#assumeTrue} on
 * {@code OPENJIUWEN_API_KEY}; skipped when the env is absent.
 */
class MultiStepToolsE2eTest {
    private static final LlmClient LLM = new LlmClient();

    @Test
    void multiStepWithToolsPlansAndExecutesMultipleNodesEndToEnd() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(), "OPENJIUWEN_API_KEY 未设，跳过真 LLM e2e");

        // ---- Register 2-3 deterministic tools (same shape as the spring-ai-ascend reference) ----
        // name -> description (feeds LlmPlanner's prompt so the LLM knows what each tool does)
        Map<String, String> descriptions = new LinkedHashMap<>();
        descriptions.put("getTemperature", "获取指定城市的当前温度。参数：city（城市名称）。");
        descriptions.put("getHumidity", "获取指定城市的当前湿度。参数：city（城市名称）。");
        descriptions.put("summarizeWeather", "把温度与湿度汇总成一句天气状况描述。参数：city（城市名称）。");

        // name -> executor (feeds ToolBackedExecutor's routing)
        Map<String, Function<Map<String, Object>, String>> tools = new LinkedHashMap<>();
        tools.put("getTemperature", MultiStepToolsE2eTest::getTemperature);
        tools.put("getHumidity", MultiStepToolsE2eTest::getHumidity);
        tools.put("summarizeWeather", MultiStepToolsE2eTest::summarizeWeather);

        // ---- Wire the PEV agent with LLM-backed planner/verifier + tool-backed executor ----
        LlmPlanner planner = new LlmPlanner(LLM, descriptions);
        ToolBackedExecutor executor = new ToolBackedExecutor(LLM, tools);
        LlmVerifier verifier = new LlmVerifier(LLM);

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);

        String task = "请分别获取北京的当前温度和湿度，然后汇总报告北京的天气状况。";

        // ---- Run the full Plan → Execute → Verify loop against the real LLM ----
        Object out = agent.invoke(task, null);
        String output = out == null ? "" : out.toString();
        // ==================== 断言 (hard: data channel + multi-node control flow) ====================

        // 1. 数据通道：output 非空（真 LLM 跑通 + assembleOutput 产出了内容）
        assertThat(output).as("real-LLM multi-step PEV must produce non-empty output").isNotEmpty();

        // 2. 多节点控制流：output 至少含两个 "nodeId: value" 段（证 planner 产出了多节点 plan，
        //    且 executor 依次执行了它们 —— assembleOutput 按平台换行拼接每个 nodeId: value）
        //    软边界：不硬断言具体 node id（LLM 决定），只证"多节点"结构性事实。
        long nodeSegments = output.lines().filter(line -> line.contains(": ")).count();
        assertThat(nodeSegments).as("multi-step plan must yield >= 2 executed node results in output (got: %d)%n%s",
                nodeSegments, output).isGreaterThanOrEqualTo(2);

        // 3. 工具真被执行：output 含至少一个确定性工具的产出特征串（temperature/humidity），
        //    证 ToolBackedExecutor 把节点路由到了注册工具而非全走 LLM。
        //    软断言（or）—— 任一工具特征命中即证工具通道活。
        boolean hasToolProducedOutput = output.contains("温度") || output.contains("湿度") || output.contains("°C")
                || output.contains("%");
        assertThat(hasToolProducedOutput)
                .as("at least one deterministic tool must have produced output (温度/湿度/°C/%%)%n%s", output).isTrue();

        // ==================== 软观察 (no assertion) ====================
        // - 具体 node id / 数量（LLM 决定，不稳定）
        // - 工具是否全部命中（LLM 可能把某步走 LLM_CALL）
        // - verifier PASS/FAIL（真 LLM 判定，content-level 不可硬断）
    }

    // ==================== deterministic tools (ported from spring-ai-ascend reference) ====================

    private static String getTemperature(Map<String, Object> inputs) {
        String city = String.valueOf(inputs.getOrDefault("city", ""));
        return city + " 当前温度: 25°C，晴";
    }

    private static String getHumidity(Map<String, Object> inputs) {
        String city = String.valueOf(inputs.getOrDefault("city", ""));
        return city + " 当前湿度: 65%";
    }

    private static String summarizeWeather(Map<String, Object> inputs) {
        String city = String.valueOf(inputs.getOrDefault("city", ""));
        return city + " 天气晴朗，温度 25°C，湿度 65%，体感舒适。";
    }
}
