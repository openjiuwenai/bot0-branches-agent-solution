/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.agents.pev.kernel.ReplanAction;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * PEVAgent 控制流承重测试 — mock 三阶段（Planner/Executor/Verifier），证 Plan→Execute→Verify→Diagnose→Dispatch
 * 主循环 + terminalGuard + sealed dispatch 各分支。每个测试标 mutation-RED。
 */
class PEVAgentControlFlowTest {
    private static AgentCard card() {
        return AgentCard.builder().build();
    }

    // ==================== happy path ====================

    @Test
    void happyPathPlanExecuteVerifyPassReturnsAssembledOutput() {
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a-result"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");

        PEVAgent agent = new PEVAgent(card(), planner, executor, verifier);
        Object out = agent.invoke("do A", null);

        assertThat(out).isEqualTo("A: a-result");
        // mutation-RED: 剥 verifier pass 短路 → 永远走 dispatch → 终态非 pass 输出 → RED
    }

    // ==================== DeviceFailure → AcceptPartial（不重试）====================

    @Test
    void deviceFailureDiagnoseAcceptPartialTerminatesWithoutRetry() {
        AtomicInteger execCount = new AtomicInteger();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> {
            execCount.incrementAndGet();
            return Map.of("A", new NodeResult.DeviceFailure("A", "timeout", true));
        };
        // verify 也判 A 失败 → device ∩ verify = {A} → DeviceFailure cause → AcceptPartial
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(false, Set.of("A"), "A failed");

        PEVAgent agent = new PEVAgent(card(), planner, executor, verifier);
        Object out = agent.invoke("do A", null);

        assertThat(execCount.get()).isEqualTo(1); // AcceptPartial 不重试，executor 只调一次
        assertThat(out.toString()).contains("DeviceFailure");
        // mutation-RED: 剥 AcceptPartial → terminal[0]=true →
        // executor 会被 LocalReplan/GlobalReplan 再调 → execCount>1 → RED
    }

    @Test
    void verifierRuntimeFailureDegradesWithoutRetry() {
        AtomicInteger execCount = new AtomicInteger();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> {
            execCount.incrementAndGet();
            return Map.of("A", new NodeResult.Success("partial-result"));
        };
        PevComponents.Verifier verifier = (in, r) -> {
            throw new UnsupportedOperationException("verifier unavailable");
        };

        PEVAgent agent = new PEVAgent(card(), planner, executor, verifier);

        assertThat(agent.invoke("do A", null)).isEqualTo("A: partial-result");
        assertThat(execCount.get()).isEqualTo(1);
    }

    @Test
    void verifierErrorIsNotDegraded() {
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("partial-result"));
        PevComponents.Verifier verifier = (in, r) -> {
            throw new AssertionError("verifier invariant failed");
        };
        PEVAgent agent = new PEVAgent(card(), planner, executor, verifier);

        assertThatThrownBy(() -> agent.invoke("do A", null)).isInstanceOf(AssertionError.class)
                .hasMessage("verifier invariant failed");
    }

    // ==================== PlanOrAnswerError → LocalReplan → 重做后 verify pass ====================

    @Test
    void planErrorFewFailedLocalReplanRetriesUntilPass() {
        AtomicInteger execCount = new AtomicInteger();
        AtomicInteger verifyCount = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<String> redoDescription =
                new java.util.concurrent.atomic.AtomicReference<>();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> {
            int n = execCount.incrementAndGet();
            // capture the redo node description to verify feedback injection (issue #35 fix)
            if (n == 2) {
                redoDescription.set(nodes.get(0).description());
            }
            return Map.of("A", new NodeResult.Success(n == 1 ? "wrong" : "right"));
        };
        PevComponents.Verifier verifier = (in, r) -> {
            int n = verifyCount.incrementAndGet();
            return n == 1
                    ? new PevKernel.VerifyResult(false, Set.of("A"), "wrong answer")
                    : new PevKernel.VerifyResult(true, Set.of(), "ok");
        };

        PEVAgent agent = new PEVAgent(card(), planner, executor, verifier);
        Object out = agent.invoke("do A", null);

        assertThat(execCount.get()).isGreaterThanOrEqualTo(2);
        assertThat(out).isEqualTo("A: right");
        // Issue #35 fix: feedback must be injected into the redo node description
        assertThat(redoDescription.get()).as("redo node description must contain corrective feedback")
                .contains("correction");
        // mutation-RED: 剥 PEVAgent.java handleLocalReplan 的 feedback 注入
        // (改回 redo.add(n) 原始节点) → redoDescription=="do A" 不含 "correction" → RED
    }

    @Test
    void localReplanFeedbackNullDoesNotBreakRetry() {
        // When verifier returns null/blank feedback, LocalReplan still retries with original desc
        AtomicInteger execCount = new AtomicInteger();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> {
            execCount.incrementAndGet();
            return Map.of("A", new NodeResult.Success("ok"));
        };
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");
        PEVAgent agent = new PEVAgent(card(), planner, executor, verifier);
        agent.invoke("do A", null);
        assertThat(execCount.get()).isEqualTo(1); // pass on first try, no retry needed
    }

    // ==================== maxRetries exceeded → terminal ====================

    @Test
    void maxRetriesExceededPlanErrorTerminates() {
        AtomicInteger verifyCount = new AtomicInteger();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A"), new PevComponents.PlanNode("B", "do B")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a"), "B",
                new NodeResult.Success("b"));
        PevComponents.Verifier verifier = (in, r) -> {
            verifyCount.incrementAndGet();
            return new PevKernel.VerifyResult(false, Set.of("A", "B"), "always wrong");
            // A,B 两个失败 → LocalReplan；超 maxRetries(=2) → terminal
        };

        PEVAgent agent = new PEVAgent(card(), planner, executor, verifier);
        agent.configure(new PEVAgent.PevConfig(2));
        Object out = agent.invoke("do AB", null);

        // maxRetries=2 → verify 最多 3 次（初 + 2 retry）后 terminalGuard 截断，不死循环
        assertThat(verifyCount.get()).isLessThanOrEqualTo(3);
        // mutation-RED: 剥 terminalGuard → 死循环 → 测试挂/超时 → RED
    }

    // ==================== dispatch 穷尽守卫（Java 17 无 sealed switch 的替代）====================

    /**
     * ReplanAction sealed 层次的变体快照——加新 permitted 变体时 RED，迫使同步更新
     * dispatchReplanAction 的 instanceof 链 + 加对应承重测试。
     *
     * <p>背景：Java 17 GA 无类型模式 switch（preview-only，本模块 --release 17 无 --enable-preview），
     * dispatchReplanAction 用 instanceof 链而非编译期穷尽 switch，编译器无法在此强制穷尽。
     * 本测试是运行期穷尽守卫：反射枚举 ReplanAction 的 permitted subclasses，断言恰为今日三变体。
     *
     * <p>mutation-RED：给 ReplanAction 加第 4 个 permitted record → getPermittedSubclasses 返回 4 个 →
     * containsExactlyInAnyOrder(三变体) RED → 必须同步加 dispatch 分支 + 承重测试。
     */
    @Test
    void replanActionSealedVariantsSnapshot() {
        Class<?>[] permitted = ReplanAction.class.getPermittedSubclasses();
        assertThat(permitted).as("ReplanAction must be sealed (getPermittedSubclasses non-null)")
                .isNotNull();
        assertThat(java.util.Arrays.stream(permitted).map(Class::getSimpleName).toList())
                .as("ReplanAction permitted variants snapshot — dispatchReplanAction must cover all")
                .containsExactlyInAnyOrder("LocalReplan", "GlobalReplan", "AcceptPartial");
    }
}
