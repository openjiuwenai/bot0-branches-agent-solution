/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CriteriaReplanBridgeRail 观测 IFF 绑定测试 — 验"控制流 Exit 触发 ↔ 收对应 RailEvent"
 * （铁律⑲：每个观测断言非恒真，剥 fire 须 RED）。
 *
 * <p><b>承重语义边界（铁律①诚实标注）</b>：本测试验的是 <em>旁路 echo 绑定</em>，不是
 * 控制流真承重。真正的承重断言（forceFinish 调没调 / pushSteering 推没推 / 谁拿到 forceFinish result）
 * 在 {@link com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRailTest}
 * 里已经覆盖且更硬（直接断 ctx.hasForceFinishRequest / SteeringQueue.captured）。
 * 这里只断 IFF：当 rail 走某 Exit 时，collector 收到对应 type 的 RailEvent，
 * 且 payload（passed/violations/verified/...）与控制流一致。如果有人剥掉 fire，
 * 控制流不变（forceFinish 照样调），但 collector 收 0 → 本测试 RED。这正是观测层
 * 应有的承重：观测不能假装"被观测对象没发生"。
 *
 * <p>fixture 复用 {@code CriteriaReplanBridgeRailTest} 的 buildCtx/CaptureSteeringQueue 范式
 * （inline copy，不跨测试类继承，保持手术刀式隔离）。
 *
 * <p><b>G.ERR.02</b>: 不 catch RuntimeException（rail 内无受检异常，本测试也无 try/catch）。
 * <b>G.MET.04</b>: 不用 Object...。
 *
 * @since 2026-07
 */
class CriteriaReplanBridgeRailObservabilityTest {
    private CollectingRailEventListener collector;

    @BeforeEach
    void installCollector() {
        collector = new CollectingRailEventListener();
        // Bypass-aware: fire is a side-channel; never touches ctx. Installing a collector
        // here only changes what fire() delivers to, not the rail's control flow.
        RailTelemetry.setCurrent(RailTelemetry.noop().with(collector));
    }

    @AfterEach
    void resetToNoop() {
        RailTelemetry.setCurrent(RailTelemetry.noop());
    }

    @Test
    void verifyPassEmitsVerifyEventAndForceFinishEventVerified() {
        ReplanRail replanRail = new ReplanRail(3);
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);
        AgentCallbackContext ctx = ctxWithFinalAnswer("建议增配债券");

        rail.afterModelCall(ctx);
        // ObservingRail simulates the fan-out tail (priority MIN_VALUE) that peeks forceFinish
        new ObservingRail().afterModelCall(ctx);

        // Exit-1 control-flow precondition (sanity, not the main bearing assertion here —
        // that lives in CriteriaReplanBridgeRailTest): forceFinish was requested.
        assertThat(ctx.hasForceFinishRequest())
                .as("sanity: Exit-1 path must have requested forceFinish").isTrue();

        // IFF assertion 1: a VerifyEvent was emitted with passed=true, 0 violations
        List<RailEvent> verifyEvents = collector.ofType(RailEventType.VERIFY);
        assertThat(verifyEvents).as("verify pass must emit exactly one VerifyEvent").hasSize(1);
        if (verifyEvents.get(0) instanceof RailEvent.VerifyEvent verify) {
            assertThat(verify.passed()).as("VerifyEvent.passed must mirror violations.isEmpty()").isTrue();
            assertThat(verify.violationCount()).as("VerifyEvent.violationCount must be 0 on pass")
                    .isEqualTo(0);
            assertThat(verify.railName()).isEqualTo("CriteriaReplanBridgeRail");
        }
        // mutation-RED: strip the fire(VerifyEvent) call → collector.ofType(VERIFY) 0 → RED

        // IFF assertion 2: a ForceFinishEvent was emitted with verified=true
        List<RailEvent> forceEvents = collector.ofType(RailEventType.FORCE_FINISH);
        assertThat(forceEvents).as("verify pass must emit exactly one ForceFinishEvent").hasSize(1);
        if (forceEvents.get(0) instanceof RailEvent.ForceFinishEvent force) {
            assertThat(force.verified())
                    .as("ForceFinishEvent.verified must be true on Exit-1 (verified terminal)").isTrue();
            assertThat(force.result()).as("ForceFinishEvent.result must carry VERIFIED_KEY=true")
                    .containsEntry(CriteriaReplanBridgeRail.VERIFIED_KEY, true);
            // source_rail attribution: ObservingRail reads SOURCE_RAIL_KEY from the forceFinish
            // result map (ObservingRail.extractSource). CriteriaReplanBridgeRail.verifiedResult
            // sets SOURCE_RAIL_KEY="CriteriaReplanBridgeRail", so railName must mirror it.
            assertThat(force.railName())
                    .as("ForceFinishEvent.railName must attribute to CriteriaReplanBridgeRail via source_rail")
                    .isEqualTo("CriteriaReplanBridgeRail");
        }
        // mutation-RED: strip SOURCE_RAIL_KEY from verifiedResult result map → ObservingRail
        //   falls back to railName="ObservingRail" → isEqualTo("CriteriaReplanBridgeRail") RED
        // mutation-RED: strip the requestForceFinish(verified) call → ObservingRail sees no
        //   forceFinish request → FORCE_FINISH 0 → RED
        // mutation-RED: if verifiedResult carried verified=false instead of true → isTrue() RED
    }

    @Test
    void verifyFailUnderLimitEmitsVerifyEventAndSteeringEvent() {
        ReplanRail replanRail = new ReplanRail(3);
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);
        AgentCallbackContext ctx = ctxWithFinalAnswer("I don't know", steeringQ);

        rail.afterModelCall(ctx);
        // ObservingRail simulates the fan-out tail (priority MIN_VALUE) that peeks forceFinish
        new ObservingRail().afterModelCall(ctx);

        // Exit-2 control-flow precondition (sanity): no forceFinish, steering was pushed.
        assertThat(ctx.hasForceFinishRequest())
                .as("sanity: Exit-2 must NOT forceFinish").isFalse();
        assertThat(steeringQ.captured).as("sanity: Exit-2 must push steering").isNotEmpty();

        // IFF assertion 1: VerifyEvent passed=false, violations reported
        List<RailEvent> verifyEvents = collector.ofType(RailEventType.VERIFY);
        assertThat(verifyEvents).as("verify fail must emit exactly one VerifyEvent").hasSize(1);
        if (verifyEvents.get(0) instanceof RailEvent.VerifyEvent verify) {
            assertThat(verify.passed()).as("VerifyEvent.passed must be false on fail").isFalse();
            assertThat(verify.violationCount())
                    .as("VerifyEvent.violationCount must be >0 on fail").isGreaterThan(0);
        }
        // mutation-RED: strip fire(VerifyEvent) → VERIFY 0 → RED

        // IFF assertion 2: SteeringEvent emitted, source=CRITERIA, excerpt non-empty
        List<RailEvent> steeringEvents = collector.ofType(RailEventType.STEERING);
        assertThat(steeringEvents).as("under-limit fail must emit exactly one SteeringEvent").hasSize(1);
        if (steeringEvents.get(0) instanceof RailEvent.SteeringEvent steer) {
            assertThat(steer.source()).as("SteeringEvent.source must be CRITERIA on verify-retry")
                    .isEqualTo("CRITERIA");
            assertThat(steer.hintExcerpt()).as("SteeringEvent.hintExcerpt must be non-empty").isNotEmpty();
            assertThat(steer.queueBound())
                    .as("queueBound must mirror ctx.hasSteeringQueue() (queue provisioned here)")
                    .isTrue();
        }
        // mutation-RED: pass a constant instead of ctx.hasSteeringQueue() at fire → bare-ctx test RED
        // mutation-RED: strip fire(SteeringEvent) → STEERING 0 → RED
        // mutation-RED: strip the steeringQ.pushSteering (control flow) → sanity asserts RED
        //   (control-flow bearing still covered by CriteriaReplanBridgeRailTest)

        // IFF cross-check: exactly one ForceFinish event overall on Exit-2 = NONE.
        assertThat(collector.ofType(RailEventType.FORCE_FINISH))
                .as("Exit-2 (under-limit) must NOT emit any ForceFinishEvent").isEmpty();
    }

    @Test
    void verifyFailOnBareCtxEmitsSteeringEventWithQueueBoundFalse() {
        // issue-#13 scenario + !63 fire-precision fix: when the host never provisioned a steering
        // queue, pushSteering hits agent-core's null-guard and silently returns. The fire still
        // happens (it follows pushSteering unconditionally), so without queueBound an observer
        // would wrongly believe the hint was enqueued. queueBound must mirror
        // ctx.hasSteeringQueue()=false so observers can tell the hint was DROPPED — the whole
        // point of the fire-precision fix and the direct answer to "fire proves want, not enqueue".
        ReplanRail replanRail = new ReplanRail(3);
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);
        // Bare ctx: no .steeringQueue(...) on the builder → hasSteeringQueue()=false (issue-#13 baseline).
        AssistantMessage msg = new AssistantMessage("I don't know");
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs)
                .extra(new LinkedHashMap<>()).build();

        rail.afterModelCall(ctx);
        new ObservingRail().afterModelCall(ctx);

        // sanity: bare ctx → hasSteeringQueue() false (the silent-drop precondition).
        assertThat(ctx.hasSteeringQueue())
                .as("sanity: bare ctx has no provisioned steering queue").isFalse();

        // The fire still fires (it follows pushSteering unconditionally) — but now it must
        // honestly report queueBound=false so observers know the hint was dropped, not enqueued.
        List<RailEvent> steeringEvents = collector.ofType(RailEventType.STEERING);
        assertThat(steeringEvents).as("fire still happens after silent-drop pushSteering").hasSize(1);
        if (steeringEvents.get(0) instanceof RailEvent.SteeringEvent steer) {
            assertThat(steer.queueBound())
                    .as("queueBound=false must signal the hint was dropped (queue not provisioned, issue #13)")
                    .isFalse();
        }
        // mutation-RED: hardcode queueBound=true at fire (pass a constant, ignore
        //   ctx.hasSteeringQueue()) → this isFalse() RED. This is THE mutation the field exists
        //   to catch — paired with the provisioned-queue test above (queueBound=true) which catches
        //   the symmetric hardcode=false mutation. Two-direction coverage of the IFF.
    }

    @Test
    void verifyFailOverLimitEmitsForceFinishEventDegraded() {
        ReplanRail replanRail = new ReplanRail(1); // max=1 → overLimit on 2nd call
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);
        Map<String, Object> invocationExtra = new LinkedHashMap<>();

        // First fail: under limit → Exit-2 (drains into the same collector, but we assert
        // the OVER-limit event below, so first fire is just to advance the counter).
        rail.afterModelCall(ctxWithFinalAnswer("I don't know", new CaptureSteeringQueue(), invocationExtra));

        // Reset collector so only the over-limit round's events are inspected — surgical,
        // isolates Exit-3 from the prior Exit-2 fire.
        collector.clear();

        // Second fail: over limit (count=2 > max=1) → Exit-3 degraded forceFinish
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        AgentCallbackContext ctx2 = ctxWithFinalAnswer("I don't know either", steeringQ, invocationExtra);
        rail.afterModelCall(ctx2);
        new ObservingRail().afterModelCall(ctx2);

        // Exit-3 control-flow precondition (sanity): forceFinish requested, no steering.
        assertThat(ctx2.hasForceFinishRequest())
                .as("sanity: Exit-3 must fire requestForceFinish(degraded)").isTrue();
        assertThat(steeringQ.captured)
                .as("sanity: Exit-3 must NOT push steering (terminal, no retry)").isEmpty();

        // IFF assertion: ForceFinishEvent emitted with verified=false + degraded payload.
        List<RailEvent> forceEvents = collector.ofType(RailEventType.FORCE_FINISH);
        assertThat(forceEvents).as("over-limit fail must emit exactly one ForceFinishEvent").hasSize(1);
        if (forceEvents.get(0) instanceof RailEvent.ForceFinishEvent force) {
            assertThat(force.verified())
                    .as("ForceFinishEvent.verified must be false on Exit-3 (degraded terminal)").isFalse();
            assertThat(force.result()).as("Exit-3 result must carry DEGRADED_KEY=true")
                    .containsEntry(CriteriaReplanBridgeRail.DEGRADED_KEY, true);
            assertThat(force.result()).containsKey(CriteriaReplanBridgeRail.RETRY_COUNT_KEY);
            // source_rail attribution: ObservingRail reads SOURCE_RAIL_KEY from the forceFinish
            // result map (ObservingRail.extractSource). CriteriaReplanBridgeRail.degradedResult
            // sets SOURCE_RAIL_KEY="CriteriaReplanBridgeRail", so railName must mirror it.
            assertThat(force.railName())
                    .as("ForceFinishEvent.railName must attribute to CriteriaReplanBridgeRail via source_rail")
                    .isEqualTo("CriteriaReplanBridgeRail");
        }
        // mutation-RED: strip SOURCE_RAIL_KEY from degradedResult result map → ObservingRail
        //   falls back to railName="ObservingRail" → isEqualTo("CriteriaReplanBridgeRail") RED
        // mutation-RED: strip the requestForceFinish(degraded) call → ObservingRail sees no
        //   forceFinish request → FORCE_FINISH 0 → RED
        // mutation-RED: if degradedResult used verified=true on Exit-3 → isFalse() RED (wrong-branch copy)

        // VerifyEvent also emitted for this round (every round emits verify verdict).
        assertThat(collector.ofType(RailEventType.VERIFY))
                .as("Exit-3 round must still emit a VerifyEvent (verdict before degrade)").hasSize(1);
    }

    @Test
    void toolCallRoundEmitsNoRailEvents() {
        // Negative-space IFF: a tool-call round takes neither terminal branch, so it must
        // emit ZERO RailEvents. This pins that fire is not accidentally placed on the
        // history-accumulation path.
        ReplanRail replanRail = new ReplanRail(3);
        CriteriaReplanBridgeRail rail = new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"), replanRail);

        ToolCall tc = new ToolCall();
        tc.setId("call-1");
        tc.setType("function");
        tc.setName("searchTool");
        tc.setArguments("{\"q\":\"bond\"}");
        AssistantMessage msg = new AssistantMessage("searching...");
        msg.setToolCalls(List.of(tc));
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs)
                .steeringQueue(new CaptureSteeringQueue()).build();

        rail.afterModelCall(ctx);
        // ObservingRail simulates the fan-out tail (priority MIN_VALUE) that peeks forceFinish
        new ObservingRail().afterModelCall(ctx);

        assertThat(collector.events())
                .as("tool-call round must emit no RailEvents (neither verify nor exit)").isEmpty();
        // mutation-RED: if someone added a stray fire on the tool-call accumulation path,
        //   collector.events() non-empty → RED (this is the guard).
    }

    // ---- fixtures (surgical copy of CriteriaReplanBridgeRailTest's buildCtx pattern) ----

    /**
     * Capture steering queue spy — records pushSteering calls.
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
}
