/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.verification.AngleScorer.ScoredAngle;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WidenThenConvergeRail 承重测试 — stub AngleScorer + real CriteriaVerifier 证两阶段控制流。
 *
 * <p>测试覆盖：
 * <ol>
 *   <li>发散阶段：强角度 → pushSteering 收敛 + 切收敛阶段</li>
 *   <li>发散阶段：边界角度 → pushSteering 风险提示 + 切收敛阶段</li>
 *   <li>发散阶段：弱角度 → pushSteering replan + 保持发散</li>
 *   <li>发散阶段：空角度 → pushSteering 格式指引</li>
 *   <li>发散阶段：轮次耗尽 → 强制切收敛</li>
 *   <li>收敛阶段：verify pass → forceFinish(verified=true)</li>
 *   <li>收敛阶段：verify fail + under limit → pushSteering 修正</li>
 *   <li>收敛阶段：verify fail + over limit → forceFinish(degraded=true)</li>
 *   <li>工具轮 → 累积历史，无终态操作</li>
 * </ol>
 *
 * <p>mutation-RED 每路径一个:
 * <ul>
 *   <li>出口A: 剥 pushSteering(converge) → CaptureSteeringQueue 强角度分支空 → RED</li>
 *   <li>出口B: 剥 risk hint → CaptureSteeringQueue 边界分支不含风险语 → RED</li>
 *   <li>出口C: 剥 pushSteering(replan) → CaptureSteeringQueue 弱角度分支空 → RED</li>
 *   <li>收敛-通过: 剥 forceFinish(verified) → hasForceFinishRequest false → RED</li>
 *   <li>收敛-超限: 剥 forceFinish(degraded) → hasForceFinishRequest false → RED</li>
 *   <li>发散轮次: 剥 divergentRoundCount 检查 → 无限发散永不收敛 → 收敛分支 forceFinish false → RED</li>
 * </ul>
 */
class WidenThenConvergeRailTest {

    // ==================== 发散阶段：强角度 → 切收敛 ====================

    @Test
    void divergentPhase_strongAngle_pushSteeringConverge_switchesToConvergent() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度A: 宏观政策", "分析内容...分析内容...", 4.0, 4.0, 4.0));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        // When: divergent phase → strong angle
        AgentCallbackContext ctx = ctxWithFinalAnswer("1. 角度A: 宏观政策\n分析内容...", steeringQ);
        rail.afterModelCall(ctx);

        // Then: pushSteering with converge message, no forceFinish
        assertThat(ctx.hasForceFinishRequest())
                .as("strong angle must NOT forceFinish — enters convergent phase")
                .isFalse();
        assertThat(steeringQ.captured)
                .as("strong angle must push steering with converge message")
                .isNotEmpty();
        assertThat(steeringQ.captured.get(0))
                .contains("角度A")
                .contains("深入分析");
        // mutation-RED: strip ctx.pushSteering(buildConvergeSteering) → captured empty → RED
    }

    @Test
    void divergentPhase_strongAngle_subsequentCallEntersConvergentPhase() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度A", "...分析...分析...", 4.0, 4.0, 4.0));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        // First call: divergent phase → strong angle → transition to convergent
        rail.afterModelCall(ctxWithFinalAnswer("1. 角度A\n分析...", steeringQ));
        steeringQ.captured.clear();

        // Second call: now in convergent phase → verify answer
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("建议增配债券", steeringQ);
        rail.afterModelCall(ctx2);

        // Then: convergent phase should run verify → forceFinish(verified=true)
        assertThat(ctx2.hasForceFinishRequest())
                .as("convergent phase with passing answer must forceFinish(verified=true)")
                .isTrue();
    }

    // ==================== 发散阶段：边界角度 → 收敛 + 风险提示 ====================

    @Test
    void divergentPhase_weakAngle_pushSteeringWithRiskHint() {
        // 边界角度：avg = 2.5, between weak(2.0) and strong(3.5)
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度B", "中规中矩的分析中规中矩的分析", 3.0, 2.0, 2.5));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议"), replanRail);

        // When: divergent phase → borderline angle
        AgentCallbackContext ctx = ctxWithFinalAnswer("1. 角度B\n分析内容", steeringQ);
        rail.afterModelCall(ctx);

        // Then: pushSteering with risk hint about __replan__
        assertThat(ctx.hasForceFinishRequest())
                .as("borderline angle must NOT forceFinish — enters convergent phase")
                .isFalse();
        assertThat(steeringQ.captured)
                .as("borderline angle must push steering with __replan__ risk hint")
                .isNotEmpty();
        assertThat(steeringQ.captured.get(0))
                .contains("__replan__")
                .contains("无法满足");
        // mutation-RED: strip risk hint from borderline branch → captured lacks __replan__ → RED
    }

    // ==================== 发散阶段：弱角度 → pushSteering replan ====================

    @Test
    void divergentPhase_poorAngle_pushSteeringReplan_staysDivergent() {
        // 弱角度：avg = 1.2, below weak threshold (2.0)
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度C", "短", 1.0, 1.0, 1.5));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议"), replanRail);

        // When: divergent phase → poor angle (first call, within max rounds)
        AgentCallbackContext ctx = ctxWithFinalAnswer("1. 角度C\n短内容", steeringQ);
        rail.afterModelCall(ctx);

        // Then: pushSteering asking to __replan__
        assertThat(ctx.hasForceFinishRequest())
                .as("poor angle within max rounds must NOT forceFinish")
                .isFalse();
        assertThat(steeringQ.captured)
                .as("poor angle must push steering asking for __replan__")
                .isNotEmpty();
        assertThat(steeringQ.captured.get(0))
                .contains("__replan__")
                .contains("全新角度");
        // mutation-RED: strip poor-angle pushSteering call → captured empty → RED
    }

    @Test
    void divergentPhase_poorAngle_secondCall_atMaxRounds_forceConverge() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度C", "短", 1.0, 1.0, 1.5));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议"), replanRail);

        // First call: poor angle → pushSteering replan
        rail.afterModelCall(ctxWithFinalAnswer("1. 角度C\n短内容", steeringQ));
        assertThat(steeringQ.captured.get(0)).contains("__replan__");

        // Second call: still poor, now at max rounds → force converge
        steeringQ.captured.clear();
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("1. 角度C\n还是短内容", steeringQ);
        rail.afterModelCall(ctx2);
        assertThat(steeringQ.captured.get(0))
                .as("after max divergent rounds, must push steering to converge directly")
                .contains("最佳角度")
                .contains("成功标准");
        // mutation-RED: strip max-rounds check → infinite divergent → converge steering never sent → RED
    }

    // ==================== 发散阶段：空角度 → pushSteering 格式指引 ====================

    @Test
    void divergentPhase_noAngles_pushSteeringFormatGuidance() {
        AngleScorer stubScorer = new StubAngleScorer(); // returns empty list
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议"), replanRail);

        // When: divergent phase → no angles parseable
        AgentCallbackContext ctx = ctxWithFinalAnswer("无编号的文本内容", steeringQ);
        rail.afterModelCall(ctx);

        // Then: pushSteering asking for numbered format
        assertThat(ctx.hasForceFinishRequest())
                .as("no-angle call must NOT forceFinish")
                .isFalse();
        assertThat(steeringQ.captured)
                .as("no-angle call must push steering with format guidance")
                .isNotEmpty();
        assertThat(steeringQ.captured.get(0))
                .contains("编号");
        // mutation-RED: strip empty-angle pushSteering → captured empty → RED
    }

    @Test
    void divergentPhase_noAngles_maxRounds_forceDirectOutput() {
        AngleScorer stubScorer = new StubAngleScorer(); // returns empty list
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议"), replanRail);

        // First no-angle call (round 1): format guidance
        rail.afterModelCall(ctxWithFinalAnswer("no angles", steeringQ));
        assertThat(steeringQ.captured.get(0)).contains("编号");

        // Second no-angle call (round 2): force converge
        steeringQ.captured.clear();
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("still no angles", steeringQ);
        rail.afterModelCall(ctx2);
        assertThat(steeringQ.captured.get(0))
                .as("after max rounds, must push steering to output directly")
                .contains("直接输出");
    }

    // ==================== 收敛阶段：verify pass → forceFinish(verified) ====================

    @Test
    void convergentPhase_verifyPass_forceFinishVerified() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度A", "...分析内容...", 4.0, 4.0, 4.0));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        // Step 1: Divergent → strong angle → convergent phase
        rail.afterModelCall(ctxWithFinalAnswer("1. 角度A\n分析", steeringQ));
        steeringQ.captured.clear();

        // Step 2: Convergent → verify pass ("建议增配债券" contains "建议" and "债券")
        AgentCallbackContext ctx = ctxWithFinalAnswer("建议增配债券", steeringQ);
        rail.afterModelCall(ctx);

        assertThat(ctx.hasForceFinishRequest())
                .as("convergent verify pass must fire requestForceFinish(verified=true)")
                .isTrue();
        // mutation-RED: strip forceFinish(verifiedResult) → hasForceFinishRequest false → RED
    }

    @Test
    void convergentPhase_verifyPass_resultContainsVerifiedTrue() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度A", "...分析...", 4.0, 4.0, 4.0));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        rail.afterModelCall(ctxWithFinalAnswer("1. 角度A\n分析", steeringQ));
        steeringQ.captured.clear();

        AgentCallbackContext ctx = ctxWithFinalAnswer("建议增配债券", steeringQ);
        rail.afterModelCall(ctx);

        ForceFinishRequestCapture cap = consumeForceFinish(ctx);
        assertThat(cap.result())
                .as("verified forceFinish result must contain VERIFIED_KEY=true")
                .containsEntry(WidenThenConvergeRail.VERIFIED_KEY, true);
    }

    // ==================== 收敛阶段：verify fail + under limit → pushSteering ====================

    @Test
    void convergentPhase_verifyFail_underLimit_pushSteering_noForceFinish() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度A", "...分析内容...", 4.0, 4.0, 4.0));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        // Step 1: Divergent → strong angle → convergent phase
        rail.afterModelCall(ctxWithFinalAnswer("1. 角度A\n分析", steeringQ));
        steeringQ.captured.clear();

        // Step 2: Convergent → verify fail (no keywords), under limit
        AgentCallbackContext ctx = ctxWithFinalAnswer("I don't know", steeringQ);
        rail.afterModelCall(ctx);

        assertThat(ctx.hasForceFinishRequest())
                .as("under-limit verify fail must NOT forceFinish — loop continues")
                .isFalse();
        assertThat(steeringQ.captured)
                .as("under-limit verify fail must push steering correction hint")
                .isNotEmpty();
        assertThat(steeringQ.captured.get(0))
                .contains("建议");
        // mutation-RED: strip ctx.pushSteering(buildCorrectionHint) → captured empty → RED
    }

    @Test
    void convergentPhase_verifyFail_underLimit_replanCountIncremented() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度A", "...分析内容...", 4.0, 4.0, 4.0));
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        rail.afterModelCall(ctxWithFinalAnswer("1. 角度A\n分析", steeringQ));
        steeringQ.captured.clear();

        int beforeCount = replanRail.replanCount();
        rail.afterModelCall(ctxWithFinalAnswer("I don't know", steeringQ));

        assertThat(replanRail.replanCount())
                .as("verify-fail retry in convergent phase must increment shared replan count")
                .isEqualTo(beforeCount + 1);
    }

    // ==================== 收敛阶段：verify fail + over limit → forceFinish(degraded) ====================

    @Test
    void convergentPhase_verifyFail_overLimit_forceFinishDegraded() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度A", "...分析内容...", 4.0, 4.0, 4.0));
        ReplanRail replanRail = new ReplanRail(1); // max=1 → overLimit on 2nd fail
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        // Step 1: Divergent → strong angle → convergent phase
        rail.afterModelCall(ctxWithFinalAnswer("1. 角度A\n分析", steeringQ));
        steeringQ.captured.clear();

        // Step 2: First convergent verify fail (under limit) → pushSteering
        AgentCallbackContext ctx1 = ctxWithFinalAnswer("I don't know", new CaptureSteeringQueue());
        rail.afterModelCall(ctx1);
        assertThat(ctx1.hasForceFinishRequest())
                .as("first convergent verify fail (under limit) must NOT forceFinish")
                .isFalse();

        // Step 3: Second convergent verify fail (over limit, count=2 > max=1)
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("I don't know either", steeringQ);
        rail.afterModelCall(ctx2);
        assertThat(ctx2.hasForceFinishRequest())
                .as("over-limit verify fail must fire requestForceFinish(degraded)")
                .isTrue();
        // mutation-RED: strip forceFinish(degradedResult) → hasForceFinishRequest false → RED
    }

    @Test
    void convergentPhase_verifyFail_overLimit_resultContainsDegradedTrue() {
        AngleScorer stubScorer = new StubAngleScorer(
                new ScoredAngle("角度A", "...分析内容...", 4.0, 4.0, 4.0));
        ReplanRail replanRail = new ReplanRail(1);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        rail.afterModelCall(ctxWithFinalAnswer("1. 角度A\n分析", steeringQ));
        steeringQ.captured.clear();

        // First fail (under limit)
        rail.afterModelCall(ctxWithFinalAnswer("I don't know", new CaptureSteeringQueue()));
        // Second fail (over limit)
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("I don't know either", steeringQ);
        rail.afterModelCall(ctx2);

        ForceFinishRequestCapture cap = consumeForceFinish(ctx2);
        assertThat(cap.result())
                .as("over-limit forceFinish result must contain DEGRADED_KEY=true")
                .containsEntry(WidenThenConvergeRail.DEGRADED_KEY, true);
    }

    // ==================== 工具轮 → 累积历史，无终态操作 ====================

    @Test
    void toolCallRound_accumulatesDecisionHistory_noTerminalDecision() {
        AngleScorer stubScorer = new StubAngleScorer();
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        WidenThenConvergeRail rail = new WidenThenConvergeRail(
                stubScorer, new RuleBasedCriteriaVerifier(),
                List.of("建议"), replanRail);

        // When: tool-call message (not final answer)
        ToolCall tc = new ToolCall();
        tc.setId("call-1");
        tc.setType("function");
        tc.setName("searchTool");
        tc.setArguments("{\"q\":\"data\"}");

        AssistantMessage msg = new AssistantMessage("searching...");
        msg.setToolCalls(List.of(tc));

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .agent(new Object())
                .inputs(inputs)
                .steeringQueue(steeringQ)
                .build();

        rail.afterModelCall(ctx);

        // Then: no terminal decision
        assertThat(ctx.hasForceFinishRequest())
                .as("tool-call round must NOT forceFinish")
                .isFalse();
        assertThat(steeringQ.captured)
                .as("tool-call round must NOT push steering")
                .isEmpty();
    }

    // ==================== helpers ====================

    /**
     * Stub AngleScorer that returns a fixed list of ScoredAngle on every call.
     * Use no-arg constructor to return empty list (simulating unparseable output).
     */
    static class StubAngleScorer extends AngleScorer {
        private final List<ScoredAngle> fixedAngles;

        StubAngleScorer(ScoredAngle... angles) {
            this.fixedAngles = angles.length == 0 ? List.of() : List.of(angles);
        }

        @Override
        public List<ScoredAngle> extractAngles(String output, List<String> criteria) {
            return fixedAngles;
        }
    }

    static class CaptureSteeringQueue implements SteeringQueue {
        final List<String> captured = new ArrayList<>();

        @Override
        public synchronized void pushSteering(String hint) {
            captured.add(hint);
        }

        @Override
        public synchronized List<String> drainSteering() {
            List<String> result = List.copyOf(captured);
            captured.clear();
            return result;
        }
    }

    record ForceFinishRequestCapture(Map<String, Object> result) {}

    private static ForceFinishRequestCapture consumeForceFinish(AgentCallbackContext ctx) {
        var req = ctx.consumeForceFinish();
        if (req == null) {
            return new ForceFinishRequestCapture(Map.of());
        }
        return new ForceFinishRequestCapture(req.getResult() != null ? req.getResult() : Map.of());
    }

    private static AgentCallbackContext ctxWithFinalAnswer(String answer) {
        return ctxWithFinalAnswer(answer, new CaptureSteeringQueue());
    }

    private static AgentCallbackContext ctxWithFinalAnswer(String answer, SteeringQueue steeringQ) {
        AssistantMessage msg = new AssistantMessage(answer);
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);
        return AgentCallbackContext.builder()
                .agent(new Object())
                .inputs(inputs)
                .steeringQueue(steeringQ)
                .build();
    }
}
