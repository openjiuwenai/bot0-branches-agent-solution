/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-LLM e2e — port of {@code RealLlmFusionE2eTest.fusionRootCauseToolExceptionDegrades}
 * (spring-ai-ascend) into the PEV module.
 *
 * <p>Scenario: a registered tool always throws → {@link ToolBackedExecutor} maps the throw to
 * {@link NodeResult.DeviceFailure} → the real-LLM {@link LlmVerifier} judges FAIL →
 * {@link PevKernel#diagnoseRootCause} finds a DeviceFailure node intersecting the verify
 * failed-set → {@link PevKernel#toReplanAction} dispatches {@code AcceptPartial} →
 * {@link PEVAgent} terminates honestly with a degraded result, <b>never retrying</b> the
 * broken device (executor invoked exactly once).
 *
 * <p><b>Hard断言</b> (this test carries control-flow evidence, unlike the soft-observe
 * {@link PEVAgentRealLlmE2eTest}):
 * <ul>
 *   <li>output contains a {@code DeviceFailure} marker — the structural-failure class
 *       name surfaced by {@link PEVAgent}'s {@code assembleOutput} on a non-Success node.</li>
 *   <li>{@code executor.execute(...)} is called exactly once — {@code AcceptPartial} is
 *       terminal; a retry would re-raise the same error against a broken device.</li>
 * </ul>
 *
 * <p>Gated by {@link LlmClient#envPresent()} ({@code OPENJIUWEN_API_KEY} /
 * {@code OPENJIUWEN_BASE_URL} / {@code OPENJIUWEN_MODEL} = BigModel GLM, OpenAI-compatible).
 */
class DeviceFailureDegradesE2eTest {

    /** Always-throw tool — simulates an unreachable external data source. */
    private static final String FAILING_TOOL = "fetchExternalData";
    private static final String FAILING_TOOL_DESC = "获取外部数据（当前不可用，会抛异常）";
    private static final String FAILING_ERROR = "连接超时：外部数据 API 不可达";

    @Test
    void deviceFailureDegrades_toolThrows_acceptPartialNoRetry() {
        org.junit.jupiter.api.Assumptions.assumeTrue(LlmClient.envPresent(),
                "OPENJIUWEN_API_KEY 未设，跳过真 LLM e2e");

        LlmClient llm = new LlmClient();

        // Always-throw tool fn — maps to NodeResult.DeviceFailure via ToolBackedExecutor.
        Function<Map<String, Object>, String> failingTool = inputs -> {
            throw new RuntimeException(FAILING_ERROR);
        };
        Map<String, Function<Map<String, Object>, String>> tools =
                Map.of(FAILING_TOOL, failingTool);

        LlmPlanner planner = new LlmPlanner(llm, Map.of(FAILING_TOOL, FAILING_TOOL_DESC));

        // Wrap the executor to count execute(...) invocations — the no-retry hard断言.
        AtomicInteger executeInvocations = new AtomicInteger(0);
        ToolBackedExecutor delegate = new ToolBackedExecutor(llm, tools);
        PevComponents.Executor countingExecutor = nodes -> {
            executeInvocations.incrementAndGet();
            return delegate.execute(nodes);
        };

        LlmVerifier verifier = new LlmVerifier(llm);

        PEVAgent agent = new PEVAgent(
                AgentCard.builder().build(), planner, countingExecutor, verifier);

        String task = """
                请执行以下两步任务：
                1. 调用 %s 获取外部数据。
                2. 基于获取到的数据，用一句话总结结论。
                如果工具返回错误，请如实报告错误，不要编造数据。""".formatted(FAILING_TOOL);

        Object out = agent.invoke(task, null);
        String output = out == null ? "" : out.toString();

        System.out.println("[pev-device-failure-e2e] output:\n" + output);
        System.out.println("[pev-device-failure-e2e] executor.execute 调用次数: "
                + executeInvocations.get());

        // ===== Hard断言 1: output 含 DeviceFailure 标记 =====
        // assembleOutput renders a non-Success node as "[DeviceFailure]".
        assertThat(output)
                .as("工具异常应被映射为 NodeResult.DeviceFailure，output 含 DeviceFailure 标记")
                .contains("DeviceFailure");

        // ===== Hard断言 2: executor 只调一次（AcceptPartial 不重试） =====
        assertThat(executeInvocations.get())
                .as("DeviceFailure → RootCause.DeviceFailure → AcceptPartial（终态），"
                        + "executor.execute 不应重试（重试只会对坏设备重抛同一错误）")
                .isEqualTo(1);

        // 软观察：degraded output 里应能看出错误线索（真 LLM 可能让 verifier 写不同措辞，
        // 故只 soft-observe，不进 hard断言）。
        if (!output.toLowerCase().contains("timeout")
                && !output.contains("超时") && !output.contains("不可达")) {
            System.out.println("[pev-device-failure-e2e] (soft) output 未含超时线索，"
                    + "可接受——硬断言已由 DeviceFailure 标记 + 不重试覆盖。");
        }
    }
}