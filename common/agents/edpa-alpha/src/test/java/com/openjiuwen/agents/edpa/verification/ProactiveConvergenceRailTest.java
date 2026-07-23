/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.reactrails.observability.RailEvent;
import com.openjiuwen.agents.reactrails.observability.RailTelemetry;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * ProactiveConvergenceRail bearing tests — IFF mutation-RED anchors.
 *
 * <p>State isolation (RailInvocationState): tests use the SAME ctx across afterModelCall
 * rounds (state accumulates in ctx.extra), mirroring react-rails ReplanRailTest. Each round
 * calls addToolRound(ctx, ...) which mutates the ctx's inputs (accumulates ToolMessages).
 *
 * @since 2026-07
 */
class ProactiveConvergenceRailTest {
    @Test
    void lowCoverageFlatlineTriggersConvergenceSteering() {
        CriteriaVerifier v = new RuleBasedCriteriaVerifier();
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(v, List.of("营收", "估值"));
        CaptureSteeringQueue q = new CaptureSteeringQueue();
        AgentCallbackContext ctx = buildCtx(q);

        addToolRound(ctx, "查询中");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "正在获取");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "继续处理");
        rail.afterModelCall(ctx);

        assertThat(rail.triggerCount(ctx)).as("3 flatlined-low tool rounds must trigger convergence steering once")
                .isEqualTo(1);
        assertThat(q.captured).as("steering pushed").hasSize(1);
        assertThat(q.captured.get(0)).contains("主动收敛");
        // mutation-RED: strip ctx.pushSteering(...) → q.captured empty → RED
    }

    @Test
    void risingCoverageDoesNotTrigger() {
        CriteriaVerifier v = new RuleBasedCriteriaVerifier();
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(v, List.of("营收", "估值"));
        AgentCallbackContext ctx = buildCtx();

        addToolRound(ctx, "查询中");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "营收数据");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "估值信息");
        rail.afterModelCall(ctx);

        assertThat(rail.triggerCount(ctx)).as("rising coverage (0→0.5→1.0) is NOT a stall — must not trigger").isZero();
        assertThat(rail.coverageHistory(ctx).stream().toList()).containsExactly(0.0, 0.5, 1.0);
        // mutation-RED: strip coverage computation (hardcode) → coverageHistory fails → RED
    }

    @Test
    void highCoverageFlatlineDoesNotTrigger() {
        CriteriaVerifier v = new RuleBasedCriteriaVerifier();
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(v, List.of("营收", "估值"));
        AgentCallbackContext ctx = buildCtx();

        addToolRound(ctx, "营收 估值");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "补充");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "更多");
        rail.afterModelCall(ctx);

        assertThat(rail.triggerCount(ctx)).as("flatlined at high coverage = on-track, not divergence").isZero();
        // mutation-RED: strip `coverage < coverageCritical` gate → triggers on-track → RED
    }

    @Test
    void finalAnswerRoundSkipped() {
        CriteriaVerifier v = new RuleBasedCriteriaVerifier();
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(v, List.of("营收", "估值"));
        CaptureSteeringQueue q = new CaptureSteeringQueue();
        AgentCallbackContext ctx = buildCtx(q);

        addToolRound(ctx, "无");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "无");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "无");
        rail.afterModelCall(ctx);
        int triggersAfterStall = rail.triggerCount(ctx);

        setFinalAnswer(ctx, "最终报告");
        rail.afterModelCall(ctx);

        assertThat(rail.toolRoundCount(ctx)).as("final-answer round must not count as a tool round").isEqualTo(3);
        assertThat(rail.triggerCount(ctx)).as("final-answer round must not change trigger count")
                .isEqualTo(triggersAfterStall);
        // mutation-RED: strip tool-calls-empty guard → fires on final answer → toolRoundCount=4 → RED
    }

    @Test
    void sustainedStallFiresOnlyOnce() {
        CriteriaVerifier v = new RuleBasedCriteriaVerifier();
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(v, List.of("营收", "估值"));
        CaptureSteeringQueue q = new CaptureSteeringQueue();
        AgentCallbackContext ctx = buildCtx(q);

        for (int i = 1; i <= 6; i++) {
            addToolRound(ctx, "无");
            rail.afterModelCall(ctx);
        }

        assertThat(rail.triggerCount(ctx)).as("6 rounds sustained stall must fire EXACTLY once (edge-triggered)")
                .isEqualTo(1);
        assertThat(q.captured).as("exactly one steering, not one per round").hasSize(1);
        // mutation-RED: remove wasStalled edge-trigger guard → fires every round → triggerCount=4 → RED
    }

    @Test
    void stateDoesNotCrossInvocationContexts() {
        CriteriaVerifier v = new RuleBasedCriteriaVerifier();
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(v, List.of("营收", "估值"));
        AgentCallbackContext first = buildCtx();
        AgentCallbackContext second = buildCtx();

        addToolRound(first, "无");
        rail.afterModelCall(first);
        addToolRound(first, "无");
        rail.afterModelCall(first);
        addToolRound(first, "无");
        rail.afterModelCall(first);
        addToolRound(second, "营收 估值 数据");
        rail.afterModelCall(second);

        assertThat(rail.triggerCount(first)).as("first invocation stalled → triggered").isEqualTo(1);
        assertThat(rail.triggerCount(second)).as("second invocation fresh state — its own coverage rising, no trigger")
                .isZero();
    }

    @Test
    void priorityIsSeventy() {
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(new RuleBasedCriteriaVerifier(), List.of("营收"));
        assertThat(rail.getPriority()).as("priority 70 fires after ExploreRail(90), before CriteriaReplanBridgeRail")
                .isEqualTo(70);
    }

    @Test
    void constructorRejectsInvalidArguments() {
        CriteriaVerifier v = new RuleBasedCriteriaVerifier();
        assertThatThrownBy(() -> new ProactiveConvergenceRail(null, List.of("营收")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProactiveConvergenceRail(v, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProactiveConvergenceRail(v, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProactiveConvergenceRail(v, List.of("营收"), 0, 0.34))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ============= MAJOR #2: SteeringEvent fire (4-lens 补测) =============

    /**
     * Convergence stall must fire a RailTelemetry SteeringEvent (MAJOR #2: observability
     * "加而未测" — fire was no-op under default NOOP telemetry; this test installs a collector
     * and asserts the event).
     *
     * <p>mutation-RED: strip RailTelemetry.current().fire(...) in ProactiveConvergenceRail →
     * collector empty → RED.
     */
    @Test
    void convergenceStallFiresSteeringEvent() {
        List<RailEvent> captured = new ArrayList<>();
        RailTelemetry.install(e -> captured.add(e));
        try {
            CriteriaVerifier v = new RuleBasedCriteriaVerifier();
            CaptureSteeringQueue q = new CaptureSteeringQueue();
            ProactiveConvergenceRail rail = new ProactiveConvergenceRail(v, List.of("营收", "估值"));
            AgentCallbackContext ctx = buildCtx(q);

            addToolRound(ctx, "查询中");
            rail.afterModelCall(ctx);
            addToolRound(ctx, "正在获取");
            rail.afterModelCall(ctx);
            addToolRound(ctx, "继续处理");
            rail.afterModelCall(ctx);

            assertThat(captured).as("stall must fire >=1 SteeringEvent").isNotEmpty();
            assertThat(captured.stream().filter(e -> e instanceof RailEvent.SteeringEvent)
                    .map(e -> (RailEvent.SteeringEvent) e)
                    .anyMatch(se -> "ProactiveConvergenceRail".equals(se.railName())
                            && "CONVERGENCE_STALL".equals(se.source())))
                    .as("SteeringEvent must carry railName=ProactiveConvergenceRail + source=CONVERGENCE_STALL")
                    .isTrue();
        } finally {
            RailTelemetry.setCurrent(null);
        }
    }

    // ============= MAJOR #3: isReachable gate 双分支 (4-lens 补测, 铁律⑰) =============

    /**
     * MAJOR #3a: toolless consumer (agent=Object, NOT BaseAgent) → isReachable=false →
     * feedback must contain the tool-agnostic fallback ("或调整当前分析方法") and must NOT
     * contain __replan__.
     */
    @Test
    void gateUnreachableFallbackExcludesReplanToolName() {
        CaptureSteeringQueue q = new CaptureSteeringQueue();
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(
                new RuleBasedCriteriaVerifier(), List.of("营收", "估值"));
        AgentCallbackContext ctx = buildCtx(q);
        addToolRound(ctx, "查询中");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "正在获取");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "继续处理");
        rail.afterModelCall(ctx);

        assertThat(q.captured).hasSize(1);
        assertThat(q.captured.get(0)).as("toolless consumer → fallback clause")
                .contains("或调整当前分析方法");
        assertThat(q.captured.get(0)).as("toolless consumer → must NOT reference __replan__")
                .doesNotContain(ReplanTool.TOOL_NAME);
    }

    /**
     * MAJOR #3b: ReplanTool registered on a real ReActAgent → isReachable=true →
     * feedback must contain __replan__ (ReplanTool.TOOL_NAME).
     *
     * <p>mutation-RED: strip ReplanTool.registerOnto → isReachable=false → fallback clause → RED.
     */
    @Test
    void gateReachableClauseIncludesReplanToolName() {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("conv-gate-reach").build());
        ReplanTool.registerOnto(agent);
        CaptureSteeringQueue q = new CaptureSteeringQueue();
        ProactiveConvergenceRail rail = new ProactiveConvergenceRail(
                new RuleBasedCriteriaVerifier(), List.of("营收", "估值"));
        ModelCallInputs inputs = new ModelCallInputs();
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(agent).inputs(inputs)
                .steeringQueue(q).build();

        addToolRound(ctx, "查询中");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "正在获取");
        rail.afterModelCall(ctx);
        addToolRound(ctx, "继续处理");
        rail.afterModelCall(ctx);

        assertThat(q.captured).hasSize(1);
        assertThat(q.captured.get(0)).as("reachable consumer → feedback must reference __replan__")
                .contains(ReplanTool.TOOL_NAME);
    }

    // ---- helpers: same ctx across afterModelCall (state accumulates), inputs mutated per round ----

    private static AgentCallbackContext buildCtx() {
        return buildCtx(new CaptureSteeringQueue());
    }

    private static AgentCallbackContext buildCtx(SteeringQueue q) {
        ModelCallInputs inputs = new ModelCallInputs();
        return AgentCallbackContext.builder().agent(new Object()).inputs(inputs).steeringQueue(q).build();
    }

    private static void addToolRound(AgentCallbackContext ctx, String toolResult) {
        ModelCallInputs inputs = requireModelCallInputs(ctx);
        List<Object> messages = inputs.getMessages() == null
                ? new ArrayList<>()
                : new ArrayList<>(inputs.getMessages());
        ToolMessage tm = new ToolMessage();
        tm.setContent(toolResult);
        messages.add(tm);
        ToolCall tc = new ToolCall();
        tc.setName("dummy_tool");
        AssistantMessage msg = new AssistantMessage("thinking");
        msg.setToolCalls(List.of(tc));
        inputs.setMessages(messages);
        inputs.setResponse(msg);
    }

    private static void setFinalAnswer(AgentCallbackContext ctx, String answer) {
        requireModelCallInputs(ctx).setResponse(new AssistantMessage(answer));
    }

    /**
     * Narrows the callback context's EventInputs to ModelCallInputs with a runtime guard,
     * avoiding unchecked blind casts (G.TYP.13).
     *
     * @param ctx the callback context carrying the model-call inputs
     * @return the narrowed ModelCallInputs
     * @throws IllegalStateException if the inputs are not a ModelCallInputs instance
     */
    private static ModelCallInputs requireModelCallInputs(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs)) {
            throw new IllegalStateException("expected ModelCallInputs but got: " + ctx.getInputs());
        }
        return (ModelCallInputs) ctx.getInputs();
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
}
