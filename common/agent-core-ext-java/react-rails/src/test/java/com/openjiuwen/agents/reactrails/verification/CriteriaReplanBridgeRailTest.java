/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

        int beforeCount = replanRail.replanCount();
        rail.afterModelCall(ctxWithFinalAnswer("I don't know", steeringQ));

        assertThat(replanRail.replanCount()).as("verify-fail retry must increment shared replan count")
                .isEqualTo(beforeCount + 1);
    }
    @Test
    void verifyFailOverLimitForceFinishDegraded() {
        ReplanRail replanRail = new ReplanRail(1); // max=1 → overLimit on 2nd call
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        // First fail: under limit → pushSteering, no forceFinish
        AgentCallbackContext ctx1 = ctxWithFinalAnswer("I don't know", new CaptureSteeringQueue());
        rail.afterModelCall(ctx1);
        assertThat(ctx1.hasForceFinishRequest()).as("first fail (under limit) must NOT forceFinish").isFalse();

        // Second fail: over limit (count=2 > max=1) → forceFinish degraded
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("I don't know either", steeringQ);
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

        // First fail (under limit)
        rail.afterModelCall(ctxWithFinalAnswer("I don't know", new CaptureSteeringQueue()));

        // Second fail (over limit)
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("I don't know either", steeringQ);
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
        AssistantMessage msg = new AssistantMessage(answer);
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);
        return AgentCallbackContext.builder().agent(new Object()).inputs(inputs).steeringQueue(steeringQ).build();
    }
}
