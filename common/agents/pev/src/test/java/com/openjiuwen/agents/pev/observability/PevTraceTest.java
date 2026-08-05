/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.kernel.NodeResult;
import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.agents.pev.kernel.ReplanAction;
import com.openjiuwen.agents.pev.kernel.RootCause;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PevTrace observability承重测试 —— content-IFF 钉死 PEVAgent.invoke 的状态机转移被 trace 捕获，
 * 非弱断言。三场景覆盖三条暗转移（Verify/Diagnose/Dispatch）+ 终态归因 + 实例 scope 隔离 + sink 异常隔离。
 *
 * <p>承重契约（CLAUDE.md beta-bearing-hardening gate）：每断言用 sealed-type identity
 * （{@code isInstanceOfSatisfying} + payload component），非"有 phase fire"。mutation-RED 注释标
 * 剥哪行 append → 对应 Phase 缺失 → RED。
 */
class PevTraceTest {
    // ==================== content-IFF: DeviceFailure → Diagnose → AcceptPartial 全转移 ====================
    /**
     * DeviceFailure 场景的 trace 必须含确切 5-phase 序列 + Diagnosed payload=RootCause.DeviceFailure
     * nodes={A} + Dispatched payload=ReplanAction.AcceptPartial + terminalReason=ACCEPT_PARTIAL。
     *
     * <p>mutation-RED：
     * <ul>
     *   <li>剥 runVerifyLoop 的 {@code phases.add(new Executed(...))} → phases 缺 Executed → 索引/类型断言 RED</li>
     *   <li>剥 {@code phases.add(new Verified(vr))} → 缺 Verified → RED（补暗转移#1 钉死）</li>
     *   <li>剥 {@code phases.add(new Diagnosed(cause))} → 缺 Diagnosed → RED（补暗转移#2）</li>
     *   <li>剥 {@code phases.add(new Dispatched(action))} → 缺 Dispatched → RED（补暗转移#3）</li>
     *   <li>剥 dispatchReplanAction 的 {@code terminalReason[0]=ACCEPT_PARTIAL} → terminalReason=null → RED</li>
     * </ul>
     */
    @Test
    void deviceFailureTraceCapturesVerifyDiagnoseDispatchAcceptPartial() {
        List<PevTrace> traces = new ArrayList<>();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.DeviceFailure("A", "timeout", true));
        // verify 也判 A 失败 → device ∩ verify = {A} → RootCause.DeviceFailure → AcceptPartial
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(false, Set.of("A"), "A failed");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier, traces::add);
        agent.invoke("do A", null);

        assertThat(traces).as("sink must receive exactly one trace").hasSize(1);
        PevTrace trace = traces.get(0);
        assertThat(trace.terminalReason()).isEqualTo(PevTrace.TerminalReason.ACCEPT_PARTIAL);
        assertThat(trace.verifyIterations()).isEqualTo(1);

        List<PevTrace.Phase> phases = trace.phases();
        assertThat(phases).as("DeviceFailure scenario must produce 5 phases: "
                + "Planned→Executed→Verified→Diagnosed→Dispatched").hasSize(5);
        assertThat(phases.get(0)).isInstanceOf(PevTrace.Planned.class);
        assertThat(phases.get(1)).isInstanceOf(PevTrace.Executed.class);

        // Verified — dark transfer #1 nailed: verifier verdict observable, not just invoke-local.
        assertThat(phases.get(2)).isInstanceOfSatisfying(PevTrace.Verified.class, v -> {
            assertThat(v.verdict().isPassed()).as("verifier must report failure").isFalse();
            assertThat(v.verdict().failedNodes()).containsExactly("A");
        });
        // mutation-RED: strip phases.add(new Verified(vr)) → phases.get(2) is Diagnosed not Verified → RED

        // Diagnosed — dark transfer #2 nailed: RootCause classification observable.
        assertThat(phases.get(3)).isInstanceOfSatisfying(PevTrace.Diagnosed.class, d -> assertThat(d.cause())
                .as("root cause must be DeviceFailure with nodes={A}")
                .isInstanceOfSatisfying(RootCause.DeviceFailure.class,
                        df -> assertThat(df.nodes()).containsExactly("A")));
        // mutation-RED: strip phases.add(new Diagnosed(cause)) → phases.get(3) is Dispatched not Diagnosed → RED

        // Dispatched — dark transfer #3 nailed (single most important control-flow fact).
        assertThat(phases.get(4)).isInstanceOfSatisfying(PevTrace.Dispatched.class, d -> assertThat(d.action())
                .as("dispatch must choose AcceptPartial (no retry)")
                .isInstanceOf(ReplanAction.AcceptPartial.class));
        // mutation-RED: strip phases.add(new Dispatched(action)) → phases has size 4 → hasSize(5) RED
    }

    // ==================== content-IFF: verify-pass 短路（无 Diagnose/Dispatch）====================

    /**
     * Verify-pass 场景的 trace 必须只有 3 phase（Planned/Executed/Verified），无 Diagnose/Dispatch
     * （真分支短路，非恒 fire 全 5），terminalReason=PASSED。
     */
    @Test
    void verifyPassTraceShortCircuitsWithoutDiagnoseOrDispatch() {
        List<PevTrace> traces = new ArrayList<>();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a-result"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier, traces::add);
        Object out = agent.invoke("do A", null);

        assertThat(out).isEqualTo("A: a-result");
        assertThat(traces).hasSize(1);
        PevTrace trace = traces.get(0);
        assertThat(trace.terminalReason()).isEqualTo(PevTrace.TerminalReason.PASSED);
        List<PevTrace.Phase> phases = trace.phases();
        assertThat(phases).as("verify-pass short-circuits: Planned→Executed→Verified only (no Diagnose/Dispatch)")
                .hasSize(3);
        assertThat(phases.get(2)).isInstanceOfSatisfying(PevTrace.Verified.class,
                v -> assertThat(v.verdict().isPassed()).isTrue());
        assertThat(phases).as("no Diagnose on verify-pass short-circuit")
                .noneMatch(p -> p instanceof PevTrace.Diagnosed);
        assertThat(phases).as("no Dispatch on verify-pass short-circuit")
                .noneMatch(p -> p instanceof PevTrace.Dispatched);
    }

    // ==================== 实例 scope 隔离（非 process-wide static 的承重证据）====================

    /**
     * 两个 PEVAgent 实例各自的 sink 只收自己的 trace，零交叉污染 —— 钉死实例 scope（非 process-wide
     * static，避免并发污染 + silent-install 坑）。
     */
    @Test
    void perInstanceSinkNoCrossContamination() {
        List<PevTrace> sinkA = new ArrayList<>();
        List<PevTrace> sinkB = new ArrayList<>();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a-result"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");

        PEVAgent agentA = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier, sinkA::add);
        PEVAgent agentB = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier, sinkB::add);
        agentA.invoke("A", null);
        agentB.invoke("B", null);

        assertThat(sinkA).as("agentA sink must see only its own trace").hasSize(1);
        assertThat(sinkB).as("agentB sink must see only its own trace").hasSize(1);
        // mutation-RED: if sink were process-wide static, both agents would push to the same list → size 2
    }

    // ==================== sink 异常隔离（FutureTask bridge）====================

    /**
     * 抛异常的 sink 不能杀 invoke 控制流 —— FutureTask 隔离（照搬 react-rails RailTelemetry.invokeIsolated）。
     */
    @Test
    void throwingSinkDoesNotKillInvoke() {
        PevTraceSink throwing = trace -> {
            throw new IllegalStateException("sink boom");
        };
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a-result"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier, throwing);
        Object out = agent.invoke("do A", null);
        assertThat(out).as("invoke must return normally despite sink throwing (FutureTask isolation)")
                .isEqualTo("A: a-result");
    }

    // ==================== noop sink 默认（显式 opt-in，无 silent-install）====================

    /**
     * 默认构造（4-arg，无 sink）→ noop sink → invoke 正常，无异常（显式 opt-in，非 mandatory-install 坑）。
     */
    @Test
    void defaultConstructorUsesNoopSinkAndInvokesNormally() {
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a-result"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(true, Set.of(), "ok");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier);
        Object out = agent.invoke("do A", null);
        assertThat(out).isEqualTo("A: a-result");
    }

    // ==================== 第三态承重：MAX_RETRIES_EXCEEDED（闭合 Lens 2/4 NIT）====================

    /**
     * LocalReplan 重试到 maxRetries 耗尽 → terminalReason=MAX_RETRIES_EXCEEDED（第三态直接承重）。
     *
     * <p>mutation-RED：剥 dispatchReplanAction 的 {@code terminalReason[0]=MAX_RETRIES_EXCEEDED} 那行
     * → terminalReason=null → RED。
     */
    @Test
    void maxRetriesExhaustedReportsMaxRetriesExceededTerminal() {
        List<PevTrace> traces = new ArrayList<>();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A"), new PevComponents.PlanNode("B", "do B")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a"), "B",
                new NodeResult.Success("b"));
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(false, Set.of("A", "B"),
                "always wrong");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier, traces::add);
        agent.configure(new PEVAgent.PevConfig(2));
        agent.invoke("do AB", null);

        assertThat(traces).hasSize(1);
        PevTrace trace = traces.get(0);
        assertThat(trace.terminalReason())
                .as("maxRetries=2 exhausted (LocalReplan retries) → MAX_RETRIES_EXCEEDED terminal")
                .isEqualTo(PevTrace.TerminalReason.MAX_RETRIES_EXCEEDED);
        assertThat(trace.verifyIterations()).as("maxRetries=2 → at most 3 verify calls (initial + 2 retries)")
                .isLessThanOrEqualTo(3);
        // mutation-RED: strip terminalReason=MAX_RETRIES_EXCEEDED in dispatchReplanAction → null → RED
    }

    // ==================== 第四态承重：INCONCLUSIVE（闭合 Lens 2 MAJOR — pre-existing edge case 诚实化）====================

    /**
     * verifier 报 failedNodes 不在 plan 里 → LocalReplan 空 redo → handleLocalReplan silent return 路径
     * 必须报 terminalReason=INCONCLUSIVE（非 null），诚实标记这个 pre-existing 退化路径。
     *
     * <p>这是 Lens 2 发现的 MAJOR：previously 该路径 emit terminalReason=null 违反 PevTrace 契约。
     * 加 INCONCLUSIVE 第四态 + handleLocalReplan else 分支设定它，闭合合约。
     *
     * <p>mutation-RED：剥 handleLocalReplan 的 {@code state.terminalReason[0]=INCONCLUSIVE} else 分支
     * → terminalReason=null → RED（合约违反复现）。
     */
    @Test
    void localReplanEmptyRedoReportsInconclusiveTerminal() {
        List<PevTrace> traces = new ArrayList<>();
        PevComponents.Planner planner = in -> new PevComponents.Plan("g",
                List.of(new PevComponents.PlanNode("A", "do A")));
        PevComponents.Executor executor = nodes -> Map.of("A", new NodeResult.Success("a-result"));
        // verifier 报一个 plan 里没有的 failed node → LocalReplan redo 集为空 → INCONCLUSIVE
        PevComponents.Verifier verifier = (in, r) -> new PevKernel.VerifyResult(false, Set.of("GHOST"),
                "ghost node failed");

        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier, traces::add);
        agent.invoke("do A", null);

        assertThat(traces).hasSize(1);
        assertThat(traces.get(0).terminalReason())
                .as("LocalReplan empty-redo (verifier failed node not in plan) must report INCONCLUSIVE, never null "
                        + "(PevTrace contract: terminalReason is never null)")
                .isEqualTo(PevTrace.TerminalReason.INCONCLUSIVE);
        // mutation-RED: strip the else-branch in handleLocalReplan → terminalReason=null → RED
    }
}
