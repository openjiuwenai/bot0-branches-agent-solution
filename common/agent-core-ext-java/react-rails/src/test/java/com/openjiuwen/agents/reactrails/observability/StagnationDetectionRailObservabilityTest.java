/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.enforcing.PromptInjectionState;
import com.openjiuwen.agents.reactrails.verification.StagnationDetectionRail;
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
import java.util.stream.Stream;

/**
 * StagnationDetectionRail 观测 IFF 绑定测试 — 验"控制流 Exit 触发 ↔ 收对应 RailEvent"
 * （铁律⑲：每个观测断言非恒真，剥 fire 须 RED）。
 *
 * <p><b>承重语义边界（铁律①诚实标注）</b>：本测试验的是 <em>旁路 echo 绑定</em>，不是
 * 控制流真承重。真正的承重断言（pushSteering 推没推 / phaseOverride 设没设 / 谁拿到
 * forceFinish result）在 {@link com.openjiuwen.agents.reactrails.verification.StagnationDetectionRailTest}
 * 里已经覆盖且更硬（直接断 SteeringQueue.captured / injectionState.peekPhaseOverride /
 * ctx.hasForceFinishRequest）。这里只断 IFF：当 rail 走某 stagnation Exit 时，collector
 * 收到对应 source 的 SteeringEvent，且 payload（source/hintExcerpt）与控制流一致。
 * 如果有人剥掉 fire，控制流不变（pushSteering 照样调），但 collector 收 0 → 本测试 RED。
 * 这正是观测层应有的承重：观测不能假装"被观测对象没发生"。
 *
 * <p>fixture 复用 {@code StagnationDetectionRailTest} 的 CaptureSteeringQueue /
 * ctxWithFinalAnswer / ctxWithToolCall 范式（inline copy，不跨测试类继承，保持手术刀式隔离）。
 * ObservingRail fan-out 模拟（priority MIN_VALUE 尾部 peek forceFinish）沿用
 * {@code CriteriaReplanBridgeRailObservabilityTest} 的既定模式。
 *
 * <p><b>G.ERR.02</b>: 不 catch RuntimeException（rail 内无受检异常，本测试也无 try/catch）。
 * <b>G.MET.04</b>: 不用 Object...。
 *
 * @since 2026-07
 */
class StagnationDetectionRailObservabilityTest {
    private PromptInjectionState injectionState;
    private CollectingRailEventListener collector;

    @BeforeEach
    void installCollector() {
        injectionState = new PromptInjectionState();
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
    void outputStagnationEmitsSteeringEventWithOutputSource() {
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail(injectionState);
        // Shared invocationExtra so RailInvocationState accumulates across calls (without this,
        // each call gets a fresh state and repeats never aggregate — same lesson as
        // StagnationDetectionRailTest's shared invocationExtra field).
        Map<String, Object> invocationExtra = new LinkedHashMap<>();

        // consecutiveOutputRepeats starts at 0, so 4 identical outputs = 3 repeats = MAX_OUTPUT_REPEATS
        // → pushSteering(brake) + fire(SteeringEvent source=STAGNATION_OUTPUT).
        rail.afterModelCall(ctxWithFinalAnswer("相同输出", steeringQ, invocationExtra));
        rail.afterModelCall(ctxWithFinalAnswer("相同输出", steeringQ, invocationExtra));
        rail.afterModelCall(ctxWithFinalAnswer("相同输出", steeringQ, invocationExtra));
        rail.afterModelCall(ctxWithFinalAnswer("相同输出", steeringQ, invocationExtra));

        // Control-flow precondition (sanity, not the main bearing assertion here —
        // that lives in StagnationDetectionRailTest): steering was pushed.
        assertThat(steeringQ.captured).as("sanity: output stagnation must push steering").isNotEmpty();

        // IFF assertion: exactly one SteeringEvent with source=STAGNATION_OUTPUT, non-empty excerpt.
        List<RailEvent> steeringEvents = collector.ofType(RailEventType.STEERING);
        assertThat(steeringEvents).as("output stagnation must emit exactly one SteeringEvent").hasSize(1);
        if (steeringEvents.get(0) instanceof RailEvent.SteeringEvent steer) {
            assertThat(steer.source()).as("SteeringEvent.source must be STAGNATION_OUTPUT on output repeat")
                    .isEqualTo("STAGNATION_OUTPUT");
            assertThat(steer.railName()).isEqualTo("StagnationDetectionRail");
            assertThat(steer.hintExcerpt()).as("SteeringEvent.hintExcerpt must be non-empty").isNotEmpty();
            assertThat(steer.queueBound())
                    .as("queueBound must mirror ctx.hasSteeringQueue() (queue provisioned here)")
                    .isTrue();
        }
        // mutation-RED: pass a constant instead of ctx.hasSteeringQueue() at fire → bare-ctx test RED
        // mutation-RED: strip the fire(SteeringEvent STAGNATION_OUTPUT) call → STEERING 0 → RED
        // mutation-RED: if fire carried STAGNATION_TOOLCYCLE instead of STAGNATION_OUTPUT → isEqualTo RED
        //   (catches wrong-branch copy from the tool-cycle path)
    }

    @Test
    void toolCycleStagnationEmitsSteeringEventWithToolCycleSource() {
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail(injectionState);

        // Tool call cycle: same single-tool sequence repeated. detectToolCycle fires on even
        // history sizes where the two halves match (mirrors StagnationDetectionRailTest's
        // 10-iteration fixture): 4→1, 6→2, 8→3 (trigger), 10→4. At repeats >= MAX_TOOL_CYCLE_REPEATS(3)
        // → pushSteering(brake) + fire(SteeringEvent source=STAGNATION_TOOLCYCLE).
        ToolCall tc = new ToolCall();
        tc.setId("c1");
        tc.setName("searchTool");
        AssistantMessage msg = new AssistantMessage("searching");
        msg.setToolCalls(List.of(tc));
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        // Shared invocationExtra so RailInvocationState accumulates the tool-cycle history
        // across all 10 iterations (same pattern as StagnationDetectionRailTest.copyCtx which
        // reuses template.getExtra()).
        Map<String, Object> invocationExtra = new LinkedHashMap<>();
        AgentCallbackContext template = AgentCallbackContext.builder().agent(new Object()).inputs(inputs)
                .steeringQueue(steeringQ).build();
        template.setExtra(invocationExtra);

        for (int i = 0; i < 10; i++) {
            rail.afterModelCall(copyCtx(template));
        }

        // Control-flow precondition (sanity): phase override set on the tool-cycle branch.
        assertThat(injectionState.peekPhaseOverride()).as("sanity: tool cycle must set phase override")
                .contains("BREAK_LOOP");

        // IFF assertion: at least one SteeringEvent with source=STAGNATION_TOOLCYCLE. The exact
        // count depends on the cycle-detection cadence (even-size halves), so assert presence +
        // correct source rather than a fixed size — the rail's bearing test pins the exact
        // trigger cadence, here we pin the IFF binding.
        List<RailEvent> steeringEvents = collector.ofType(RailEventType.STEERING);
        assertThat(steeringEvents).as("tool cycle must emit at least one SteeringEvent").isNotEmpty();
        // G.TYP.13: instanceof pattern match instead of unchecked cast.
        assertThat(
                steeringEvents.stream().flatMap(e -> e instanceof RailEvent.SteeringEvent steer
                        ? Stream.of(steer.source()) : Stream.empty()))
                .as("at least one SteeringEvent.source must be STAGNATION_TOOLCYCLE")
                .contains("STAGNATION_TOOLCYCLE");
        // mutation-RED: strip the fire(SteeringEvent STAGNATION_TOOLCYCLE) call → no event with
        //   source STAGNATION_TOOLCYCLE → contains RED
        // mutation-RED: if fire carried STAGNATION_OUTPUT instead of STAGNATION_TOOLCYCLE → contains RED
        //   (catches wrong-branch copy from the output path)
    }

    @Test
    void uniqueOutputsEmitsNoSteeringEvent() {
        // Negative-space IFF: non-repeating outputs must emit ZERO SteeringEvents. Pins that
        // fire is on the brake path, not on the plain history-accumulation path.
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail(injectionState);

        rail.afterModelCall(ctxWithFinalAnswer("第一次输出", steeringQ));
        rail.afterModelCall(ctxWithFinalAnswer("第二次输出", steeringQ));
        rail.afterModelCall(ctxWithFinalAnswer("第三次输出", steeringQ));

        assertThat(collector.ofType(RailEventType.STEERING))
                .as("unique outputs must emit no SteeringEvent (no brake fired)").isEmpty();
        // mutation-RED: if someone added a stray fire on the output-history accumulation path,
        //   STEERING non-empty → RED (this is the guard).
    }

    @Test
    void persistentStagnationEmitsForceFinishEventViaObservingRail() {
        // ObservingRail fan-out simulation: business rail requests forceFinish via ctx; the
        // tail ObservingRail (priority MIN_VALUE) peeks ctx and emits ForceFinishEvent — zero
        // manual fire in StagnationDetectionRail for the terminal. This pins the fan-out wiring.
        CaptureSteeringQueue steeringQ = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail(injectionState);
        ObservingRail observingTail = new ObservingRail();
        // Shared invocationExtra so RailInvocationState accumulates totalStagnations across calls.
        Map<String, Object> invocationExtra = new LinkedHashMap<>();

        // Drive two full brake cycles (MAX_STAGNATIONS=2) so the second brake forces finish.
        // First brake cycle: 4 identical outputs = 3 repeats = MAX_OUTPUT_REPEATS → first brake
        // (totalStagnations=1, consecutiveOutputRepeats reset to 0). The forceFinish check fires
        // on the SAME ctx as the brake (totalStagnations>=MAX_STAGNATIONS), so the first round
        // does NOT forceFinish (1 < 2).
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", steeringQ, invocationExtra)); // 1st (not repeat)
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", steeringQ, invocationExtra)); // 2nd (count=1)
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", steeringQ, invocationExtra)); // 3rd (count=2)
        AgentCallbackContext ctxFirstBrake = ctxWithFinalAnswer("停滞输出", steeringQ, invocationExtra);
        rail.afterModelCall(ctxFirstBrake); // 4th (count=3 → brake, totalStagnations=1)
        observingTail.afterModelCall(ctxFirstBrake);
        assertThat(ctxFirstBrake.hasForceFinishRequest())
                .as("sanity: first brake round must NOT forceFinish yet").isFalse();

        // Reset collector so only the over-limit round's events are inspected — surgical,
        // isolates the forceFinish terminal from the prior brake fire.
        collector.clear();

        // Second brake cycle: after reset, 3 more identical outputs reach the 3rd repeat.
        // The 3rd repeat (7th output overall) → second brake + requestForceFinish(degraded)
        // (totalStagnations=2 >= MAX_STAGNATIONS), all on this ctx.
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", steeringQ, invocationExtra)); // 5th (count=1)
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", steeringQ, invocationExtra)); // 6th (count=2)
        AgentCallbackContext ctxTerminal = ctxWithFinalAnswer("停滞输出", steeringQ, invocationExtra);
        rail.afterModelCall(ctxTerminal); // 7th (count=3 → brake + forceFinish, totalStagnations=2)
        observingTail.afterModelCall(ctxTerminal);

        // Control-flow precondition (sanity): forceFinish requested with degraded payload.
        assertThat(ctxTerminal.hasForceFinishRequest())
                .as("sanity: persistent stagnation must request forceFinish(degraded)").isTrue();

        // IFF assertion: ObservingRail emitted exactly one ForceFinishEvent with verified=false
        // and source_rail=StagnationDetectionRail (degraded terminal, not verified).
        List<RailEvent> forceEvents = collector.ofType(RailEventType.FORCE_FINISH);
        assertThat(forceEvents).as("ObservingRail fan-out must emit exactly one ForceFinishEvent")
                .hasSize(1);
        if (forceEvents.get(0) instanceof RailEvent.ForceFinishEvent force) {
            assertThat(force.verified())
                    .as("ForceFinishEvent.verified must be false on degraded terminal").isFalse();
            assertThat(force.railName())
                    .as("ForceFinishEvent.railName must be attributed to StagnationDetectionRail")
                    .isEqualTo("StagnationDetectionRail");
            assertThat(force.result()).as("degraded result must carry DEGRADED_KEY=true")
                    .containsEntry(StagnationDetectionRail.DEGRADED_KEY, true);
            assertThat(force.result()).containsKey(StagnationDetectionRail.STAGNATION_REASON_KEY);
        }
        // mutation-RED: strip ObservingRail registration / its observeForceFinish fire → FORCE_FINISH 0 → RED
        // mutation-RED: if ObservingRail extracted verified=true (wrong key) → isFalse RED
        // mutation-RED: if StagnationDetectionRail stopped setting SOURCE_RAIL_KEY in result →
        //   railName falls back to "ObservingRail" → isEqualTo("StagnationDetectionRail") RED
    }

    // ---- fixtures (surgical copy of StagnationDetectionRailTest's buildCtx pattern) ----

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

    private static AgentCallbackContext ctxWithFinalAnswer(String answer, SteeringQueue steeringQ) {
        return ctxWithFinalAnswer(answer, steeringQ, new LinkedHashMap<>());
    }

    private static AgentCallbackContext ctxWithFinalAnswer(String answer, SteeringQueue steeringQ,
            Map<String, Object> invocationExtra) {
        AssistantMessage msg = new AssistantMessage(answer);
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs)
                .steeringQueue(steeringQ).build();
        ctx.setExtra(invocationExtra);
        return ctx;
    }

    /**
     * Copy a context sharing the template's invocationExtra so the stagnation rail's
     * per-invocation state accumulates across iterations (mirrors
     * StagnationDetectionRailTest.copyCtx which reuses template.getExtra()).
     *
     * @param template source callback context whose extra is shared
     * @return fresh callback context sharing invocationExtra state
     */
    private static AgentCallbackContext copyCtx(AgentCallbackContext template) {
        AgentCallbackContext copy = AgentCallbackContext.builder().agent(new Object())
                .inputs(template.getInputs()).steeringQueue(template.getSteeringQueue()).build();
        copy.setExtra(template.getExtra());
        return copy;
    }
}
