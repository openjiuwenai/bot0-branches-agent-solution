/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.verification.CriteriaVerificationRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CriteriaVerificationRail 观测 IFF 绑定测试 — 验"控制流 Exit 触发 ↔ 收对应 RailEvent"
 * （铁律⑲：每个观测断言非恒真，剥 fire 须 RED）。
 *
 * <p>镜像 {@link CriteriaReplanBridgeRailObservabilityTest} 的 buildCtx/collector/ObservingRail
 * fan-out 模拟范式，但针对 CriteriaVerificationRail 的 <b>双出口</b> 语义调整
 * （无 Exit-2 steering：verify fail 直接降级 forceFinish，无 retry 分支）：
 * <ul>
 *   <li><b>Pass</b> → VerifyEvent(passed=true) +
 *       ForceFinish(verified=true, source_rail="CriteriaVerificationRail")</li>
 *   <li><b>Fail</b> → VerifyEvent(passed=false) +
 *       ForceFinish(verified=false, source_rail="CriteriaVerificationRail")</li>
 * </ul>
 *
 * <p><b>承重语义边界（铁律①诚实标注）</b>：本测试验的是 <em>观测绑定 IFF</em>，不是
 * 控制流真承重。真正的承重断言（forceFinish 调没调 / result 内容）在
 * {@link com.openjiuwen.agents.reactrails.verification.CriteriaVerificationRailTest}
 * 里已经覆盖且更硬（直接断 result map 的 VERIFIED_KEY/DEGRADED_KEY）。
 * 这里只断 IFF：当 rail 走某 Exit 时，collector 收到对应 type 的 RailEvent，
 * 且 payload（passed/violationCount/verified/source_rail）与控制流一致。如果有人剥掉 fire
 * （VerifyEvent 的 fire 或 ObservingRail 的 peek），控制流不变（forceFinish 照样调），
 * 但 collector 收 0 → 本测试 RED。这正是观测层应有的承重：观测不能假装"被观测对象没发生"。
 *
 * <p><b>ForceFinish 归因路径（与 bridge rail 一致）</b>：CriteriaVerificationRail 自身只 fire
 * VerifyEvent；ForceFinish 不在 rail 内手动 fire，而是 {@link ObservingRail}（priority MIN_VALUE，
 * 在 fan-out 末尾 peek {@code ctx.hasForceFinishRequest()}）读取 result map 中的
 * {@link ObservingRail#SOURCE_RAIL_KEY}（="CriteriaVerificationRail"）后 emit。因此 source_rail
 * 字段断言覆盖的是 <em>归因键写入 ↔ ObservingRail 提取</em> 的 IFF：剥掉 verifiedResult /
 * degradedResult 中的 {@code put(SOURCE_RAIL_KEY, RAIL)} → source 回退为 "ObservingRail" →
 * isEqualTo("CriteriaVerificationRail") RED。
 *
 * <p>fixture 复用 {@code CriteriaReplanBridgeRailObservabilityTest} 的 buildCtx/CaptureSteeringQueue
 * 范式（inline copy，不跨测试类继承，保持手术刀式隔离）。
 *
 * <p><b>G.ERR.02</b>: 不 catch RuntimeException（rail 内无受检异常，本测试也无 try/catch）。
 * <b>G.MET.04</b>: 不用 Object...。
 *
 * @since 2026-07
 */
class CriteriaVerificationRailObservabilityTest {
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
        CriteriaVerificationRail rail = new CriteriaVerificationRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"));
        AgentCallbackContext ctx = ctxWithFinalAnswer("建议增配债券");

        rail.afterModelCall(ctx);
        // ObservingRail simulates the fan-out tail (priority MIN_VALUE) that peeks forceFinish
        new ObservingRail().afterModelCall(ctx);

        // Exit-Pass control-flow precondition (sanity, not the main bearing assertion here —
        // that lives in CriteriaVerificationRailTest): forceFinish was requested.
        assertThat(ctx.hasForceFinishRequest())
                .as("sanity: pass path must have requested forceFinish").isTrue();

        // IFF assertion 1: a VerifyEvent was emitted with passed=true, 0 violations
        List<RailEvent> verifyEvents = collector.ofType(RailEventType.VERIFY);
        assertThat(verifyEvents).as("verify pass must emit exactly one VerifyEvent").hasSize(1);
        if (verifyEvents.get(0) instanceof RailEvent.VerifyEvent verify) {
            assertThat(verify.passed()).as("VerifyEvent.passed must mirror violations.isEmpty()").isTrue();
            assertThat(verify.violationCount()).as("VerifyEvent.violationCount must be 0 on pass")
                    .isEqualTo(0);
            assertThat(verify.railName()).isEqualTo("CriteriaVerificationRail");
        }
        // mutation-RED: strip the fire(VerifyEvent) call in CriteriaVerificationRail → collector.ofType(VERIFY) 0 → RED

        // IFF assertion 2: a ForceFinishEvent was emitted (via ObservingRail peek) with verified=true
        List<RailEvent> forceEvents = collector.ofType(RailEventType.FORCE_FINISH);
        assertThat(forceEvents).as("verify pass must emit exactly one ForceFinishEvent").hasSize(1);
        if (forceEvents.get(0) instanceof RailEvent.ForceFinishEvent force) {
            assertThat(force.verified())
                    .as("ForceFinishEvent.verified must be true on pass (verified terminal)").isTrue();
            assertThat(force.railName())
                    .as("ForceFinishEvent.railName must be attributed to CriteriaVerificationRail via SOURCE_RAIL_KEY")
                    .isEqualTo("CriteriaVerificationRail");
            assertThat(force.result()).as("ForceFinishEvent.result must carry VERIFIED_KEY=true")
                    .containsEntry(CriteriaVerificationRail.VERIFIED_KEY, true);
        }
        // mutation-RED: strip the SOURCE_RAIL_KEY put in verifiedResult → railName="ObservingRail" → RED
        // mutation-RED: if ObservingRail's peek were removed → FORCE_FINISH 0 → RED
    }

    @Test
    void verifyFailEmitsVerifyEventAndForceFinishEventDegraded() {
        CriteriaVerificationRail rail = new CriteriaVerificationRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"));
        // Final answer that fails every criterion (no required keyword present)
        AgentCallbackContext ctx = ctxWithFinalAnswer("I don't know");

        rail.afterModelCall(ctx);
        // ObservingRail simulates the fan-out tail (priority MIN_VALUE) that peeks forceFinish
        new ObservingRail().afterModelCall(ctx);

        // Exit-Fail control-flow precondition (sanity): forceFinish was requested (degraded terminal).
        // Unlike bridge rail, CriteriaVerificationRail has NO Exit-2 steering branch — fail always
        // forceFinish(degraded) immediately.
        assertThat(ctx.hasForceFinishRequest())
                .as("sanity: fail path must still request forceFinish (no steering retry in this rail)")
                .isTrue();

        // IFF assertion 1: VerifyEvent passed=false, violations reported
        List<RailEvent> verifyEvents = collector.ofType(RailEventType.VERIFY);
        assertThat(verifyEvents).as("verify fail must emit exactly one VerifyEvent").hasSize(1);
        if (verifyEvents.get(0) instanceof RailEvent.VerifyEvent verify) {
            assertThat(verify.passed()).as("VerifyEvent.passed must be false on fail").isFalse();
            assertThat(verify.violationCount())
                    .as("VerifyEvent.violationCount must be >0 on fail").isGreaterThan(0);
            assertThat(verify.railName()).isEqualTo("CriteriaVerificationRail");
        }
        // mutation-RED: strip fire(VerifyEvent) → VERIFY 0 → RED

        // IFF assertion 2: ForceFinishEvent emitted with verified=false + degraded payload
        List<RailEvent> forceEvents = collector.ofType(RailEventType.FORCE_FINISH);
        assertThat(forceEvents).as("verify fail must emit exactly one ForceFinishEvent").hasSize(1);
        if (forceEvents.get(0) instanceof RailEvent.ForceFinishEvent force) {
            assertThat(force.verified())
                    .as("ForceFinishEvent.verified must be false on fail (degraded terminal)").isFalse();
            assertThat(force.railName())
                    .as("ForceFinishEvent.railName must be attributed to CriteriaVerificationRail via SOURCE_RAIL_KEY")
                    .isEqualTo("CriteriaVerificationRail");
            assertThat(force.result()).as("fail result must carry VERIFIED_KEY=false")
                    .containsEntry(CriteriaVerificationRail.VERIFIED_KEY, false);
            assertThat(force.result()).as("fail result must carry DEGRADED_KEY=true")
                    .containsEntry(CriteriaVerificationRail.DEGRADED_KEY, true);
            assertThat(force.result()).as("fail result must carry UNMET_KEY (non-empty)")
                    .containsKey(CriteriaVerificationRail.UNMET_KEY);
        }
        // mutation-RED: strip the SOURCE_RAIL_KEY put in degradedResult → railName="ObservingRail" → RED
        // mutation-RED: if fire carried verified=true instead of false → isFalse() RED (catches wrong-branch copy)
    }

    @Test
    void toolCallRoundEmitsNoRailEvents() {
        // Negative-space IFF: a tool-call round takes neither terminal branch, so it must
        // emit ZERO RailEvents. This pins that fire is not accidentally placed on the
        // history-accumulation path.
        CriteriaVerificationRail rail = new CriteriaVerificationRail(new RuleBasedCriteriaVerifier(),
                List.of("建议", "债券"));

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

    // ---- fixtures (surgical copy of CriteriaReplanBridgeRailObservabilityTest's buildCtx pattern) ----

    /**
     * No-op steering queue stub — CriteriaVerificationRail never pushes steering (no Exit-2),
     * but ctx builder requires one. Provided for parity with the bridge test fixture.
     */
    static class CaptureSteeringQueue implements SteeringQueue {
        @Override
        public void pushSteering(String hint) {
            // CriteriaVerificationRail never calls this; stub for builder completeness.
        }

        @Override
        public List<String> drainSteering() {
            return List.of();
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
