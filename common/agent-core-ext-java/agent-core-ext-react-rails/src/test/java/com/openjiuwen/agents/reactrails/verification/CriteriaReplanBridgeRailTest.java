/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * CriteriaReplanBridgeRail 承重测试 — mock context 证三出口控制流。
 *
 * <p>三出口验证：
 * <ol>
 *   <li>verify pass → requestForceFinish(verified=true)</li>
 *   <li>verify fail + under limit → pushSteering(hint), 不 forceFinish</li>
 *   <li>verify fail + over limit → requestForceFinish(degraded=true)</li>
 * </ol>
 *
 * <p>mutation-RED 每出口一个：
 * <ul>
 *   <li>出口1: 剥 forceFinish(verifiedResult) → hasForceFinishRequest false → RED</li>
 *   <li>出口2: 剥 pushSteering(...) → CaptureSteeringQueue.captured 空 → RED</li>
 *   <li>出口3: 剥 forceFinish(degradedResult) → hasForceFinishRequest false → RED</li>
 * </ul>
 */
class CriteriaReplanBridgeRailTest {
    @Test
    void verifyPassLocksVerifiedTerminal() {
        // Given: RuleBasedCriteriaVerifier matches keywords in output
        ReplanRail replanRail = new ReplanRail(3);
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        // When: afterModelCall with passing final answer ("建议增配债券" contains "建议" and "债券")
        AgentCallbackContext ctx = ctxWithFinalAnswer("建议增配债券");

        rail.afterModelCall(ctx);

        // Then: forceFinish(verified=true)
        assertThat(ctx.hasForceFinishRequest()).as("verify pass must fire requestForceFinish(verified=true)").isTrue();
        // mutation-RED: strip forceFinish(verifiedResult) → hasForceFinishRequest false → RED
    }

    @Test
    void verifyPassResultContainsVerifiedTrue() {
        ReplanRail replanRail = new ReplanRail(3);
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        AgentCallbackContext ctx = ctxWithFinalAnswer("建议增配债券");
        rail.afterModelCall(ctx);

        ForceFinishRequestCapture cap = consumeForceFinish(ctx);
        assertThat(cap.result()).as("verified forceFinish result must contain VERIFIED_KEY=true")
                .containsEntry(CriteriaReplanBridgeRail.VERIFIED_KEY, true);
    }
    @Test
    void verifyFailUnderLimitPushSteeringNoForceFinish() {
        ReplanRail replanRail = new ReplanRail(3); // max=3, first call is under limit
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        // When: afterModelCall with failing answer, under replan limit
        AgentCallbackContext ctx = ctxWithFinalAnswer("I don't know", steeringQ);
        rail.afterModelCall(ctx);

        // Then: no forceFinish, steering was pushed with correction hint
        assertThat(ctx.hasForceFinishRequest())
                .as("under-limit verify fail must NOT forceFinish — loop continues for retry").isFalse();
        assertThat(steeringQ.captured).as("under-limit verify fail must push steering correction hint").isNotEmpty();
        assertThat(steeringQ.captured.get(0)).contains("建议").contains("债券");
        // mutation-RED: strip ctx.pushSteering(...) → captured empty → RED
    }

    @Test
    void verifyFailUnderLimitReplanCountIncremented() {
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        AgentCallbackContext ctx = ctxWithFinalAnswer("I don't know", steeringQ);
        int beforeCount = replanRail.replanCount(ctx);
        rail.afterModelCall(ctx);

        assertThat(replanRail.replanCount(ctx)).as("verify-fail retry must increment shared replan count")
                .isEqualTo(beforeCount + 1);
    }
    @Test
    void verifyFailOverLimitForceFinishDegraded() {
        ReplanRail replanRail = new ReplanRail(1); // max=1 → overLimit on 2nd call
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);
        Map<String, Object> invocationExtra = new LinkedHashMap<>();

        // First fail: under limit → pushSteering, no forceFinish
        AgentCallbackContext ctx1 = ctxWithFinalAnswer("I don't know", new CaptureSteeringQueue(), invocationExtra);
        rail.afterModelCall(ctx1);
        assertThat(ctx1.hasForceFinishRequest()).as("first fail (under limit) must NOT forceFinish").isFalse();

        // Second fail: over limit (count=2 > max=1) → forceFinish degraded
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("I don't know either", steeringQ, invocationExtra);
        rail.afterModelCall(ctx2);
        assertThat(ctx2.hasForceFinishRequest()).as("over-limit verify fail must fire requestForceFinish(degraded)")
                .isTrue();
        // mutation-RED: strip forceFinish(degradedResult) → hasForceFinishRequest false → RED
    }

    @Test
    void verifyFailOverLimitResultContainsDegradedTrue() {
        ReplanRail replanRail = new ReplanRail(1);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);
        Map<String, Object> invocationExtra = new LinkedHashMap<>();

        // First fail (under limit)
        rail.afterModelCall(ctxWithFinalAnswer("I don't know", new CaptureSteeringQueue(), invocationExtra));

        // Second fail (over limit)
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("I don't know either", steeringQ, invocationExtra);
        rail.afterModelCall(ctx2);

        ForceFinishRequestCapture cap = consumeForceFinish(ctx2);
        assertThat(cap.result()).as("over-limit forceFinish result must contain DEGRADED_KEY=true")
                .containsEntry(CriteriaReplanBridgeRail.DEGRADED_KEY, true);
        assertThat(cap.result()).containsKey(CriteriaReplanBridgeRail.RETRY_COUNT_KEY);
    }
    @Test
    void toolCallRoundAccumulatesDecisionHistoryNoTerminalDecision() {
        // When: afterModelCall with a tool-call message (not final answer)
        ToolCall tc = new ToolCall();
        tc.setId("call-1");
        tc.setType("function");
        tc.setName("searchTool");
        tc.setArguments("{\"q\":\"bond\"}");

        AssistantMessage msg = new AssistantMessage("searching...");
        msg.setToolCalls(List.of(tc));

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs)
                .steeringQueue(steeringQ).build();

        ReplanRail replanRail = new ReplanRail(3);
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);
        rail.afterModelCall(ctx);

        // Then: no terminal decision (neither forceFinish nor pushSteering)
        assertThat(ctx.hasForceFinishRequest()).as("tool-call round must NOT forceFinish").isFalse();
        assertThat(steeringQ.captured).as("tool-call round must NOT push steering").isEmpty();
    }

    @Test
    void decisionHistoryDoesNotCrossInvocationContexts() {
        AtomicReference<String> observedHistory = new AtomicReference<>();
        CriteriaVerifier verifier = (criteria, output, history) -> {
            observedHistory.set(history);
            return List.of();
        };
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(verifier, List.of("criterion"), new ReplanRail(1));

        rail.afterModelCall(ctxWithToolCall("first_invocation_tool"));
        rail.afterModelCall(ctxWithFinalAnswer("second invocation answer"));

        assertThat(observedHistory.get()).as("a fresh invocation must not inherit prior bridge history").isEmpty();
    }

    @Test
    void verifyRetryBudgetDoesNotCrossInvocationContexts() {
        ReplanRail replanRail = new ReplanRail(1);
        CriteriaVerifier alwaysFail = (criteria, output, history) -> List
                .of(new com.openjiuwen.agents.reactrails.types.Violation("criterion", "missing"));
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(alwaysFail, List.of("criterion"), replanRail);

        rail.afterModelCall(ctxWithFinalAnswer("first failure"));
        AgentCallbackContext secondInvocation = ctxWithFinalAnswer("second failure");
        rail.afterModelCall(secondInvocation);

        assertThat(secondInvocation.hasForceFinishRequest())
                .as("a fresh invocation must start with a fresh criteria retry budget").isFalse();
    }
    // ==================== Site 1 handshake (issue #16 follow-up, xuefanfan 5.1 建议) ============

    /**
     * Locks the Site 1 reachability-gate handshake as a CI contract (not just a social-contract TODO).
     *
     * <p>Today {@code CriteriaReplanBridgeRail.buildGradientHint} is dead code — no react-rails
     * verifier produces {@code isPartial} metadata, so {@code hasGradient} is always false and the
     * gradient branch (which appends {@code "call __replan__"}) is never reached. The Site 1 fix
     * is therefore deliberately deferred (TODO in {@code buildGradientHint}).
     *
     * <p>This test is {@link Disabled} UNTIL a future {@code GradientVerifier} activates the
     * gradient branch. The fixer who activates it must:
     * <ol>
     *   <li>thread {@code ctx} through {@code buildCorrectionHint}/{@code buildGradientHint}, and</li>
     *   <li>gate the {@code __replan__} append on {@link ReplanTool#isReachable} (mirroring
     *       {@code PreCompletionChecklistRail}'s COVERAGE branch), then</li>
     *   <li>remove the {@code @Disabled} below.</li>
     * </ol>
     * Once enabled, this test asserts the handshake: when {@code ReplanTool} is NOT reachable
     * (bare/mock ctx), the gradient hint must fall back to a tool-agnostic hint and must NOT
     * reference {@code __replan__}. If the fixer forgets the gate, this test goes RED.
     *
     * <p>Social contract → CI contract: the fixer is forced to confront this test when activating
     * GradientVerifier, rather than relying on someone reading a TODO comment a year later.
     */
    @Disabled("Remove when a GradientVerifier activates isPartial metadata; then this test locks the "
            + "reachability-gate handshake for Site 1 (CriteriaReplanBridgeRail gradient hint). "
            + "See issue #16 Site 1 TODO in buildGradientHint.")
    @Test
    void gradientHintMustGateReplanReferenceOnReachability() {
        // Given: a fake GradientVerifier producing isPartial metadata (activates the gradient branch),
        // and a bare/mock ctx (agent=Object, not a BaseAgent → ReplanTool.isReachable returns false).
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        CriteriaVerifier gradientVerifier = (criteria, output, history) -> List.of(
                new Violation("对比矩阵", "缺失", Map.of("isPartial", Boolean.TRUE, "covered",
                        List.<String>of(), "missing", List.of("对比矩阵"))));
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(gradientVerifier,
                List.of("对比矩阵"), new ReplanRail(3));

        // When: rail fires the under-limit fail path → gradient hint → pushSteering
        AgentCallbackContext ctx = ctxWithFinalAnswer("bad answer", steeringQ);
        rail.afterModelCall(ctx);

        // Then: once the Site 1 reachability gate is wired, the gradient hint must NOT reference
        // __replan__ when ReplanTool is not reachable (mock ctx → isReachable false → tool-agnostic
        // fallback, mirroring Site 3 COVERAGE). Until the gate is wired this assertion fails, which
        // is exactly why this test is @Disabled today.
        assertThat(steeringQ.captured).as("gradient hint must be pushed").hasSize(1);
        assertThat(steeringQ.captured.get(0))
                .as("gradient hint must NOT reference __replan__ when ReplanTool is not reachable "
                        + "(reachability-gate handshake for Site 1)")
                .doesNotContain(ReplanTool.TOOL_NAME);
    }

    /**
     * Capture steering queue spy — records pushSteering calls for mutation-RED assertions.
     */
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

    /**
     * Minimal force-finish result capture — consumeForceFinish returns null
     * when not set, so we extract the result manually from the builder.
     *
     * <p>(AgentCallbackContext.builder().forceFinishRequest() is not a getter,
     * so we rely on consumeForceFinish which returns the request on the real
     * context.)
     */
    record ForceFinishRequestCapture(Map<String, Object> result) {
    }

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
        return ctxWithFinalAnswer(answer, steeringQ, new LinkedHashMap<>());
    }

    private static AgentCallbackContext ctxWithFinalAnswer(String answer, SteeringQueue steeringQ,
            Map<String, Object> invocationExtra) {
        AssistantMessage msg = new AssistantMessage(answer);
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);
        return AgentCallbackContext.builder().agent(new Object()).inputs(inputs).steeringQueue(steeringQ)
                .extra(invocationExtra).build();
    }

    private static AgentCallbackContext ctxWithToolCall(String toolName) {
        ToolCall toolCall = new ToolCall();
        toolCall.setName(toolName);
        toolCall.setArguments("{}");
        AssistantMessage message = new AssistantMessage();
        message.setToolCalls(List.of(toolCall));
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(message);
        return AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
    }
}
