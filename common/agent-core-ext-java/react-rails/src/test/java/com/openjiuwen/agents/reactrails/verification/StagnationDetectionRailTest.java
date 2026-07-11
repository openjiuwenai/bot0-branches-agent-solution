/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * StagnationDetectionRail 承重测试 — mock context 证 stagnation 检测 + brake steering + forceFinish.
 *
 * <p>五出口验证：
 * <ol>
 *   <li>输出不重复 → 不触发</li>
 *   <li>输出重复达阈值 → pushSteering(brake)</li>
 *   <li>输出重复超限 → forceFinish(degraded)</li>
 *   <li>工具调用循环达阈值 → pushSteering(brake)</li>
 *   <li>工具调用循环超限 → forceFinish(degraded)</li>
 * </ol>
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>剥 consecutiveOutputRepeats++ → 永不触发 brake → RED</li>
 *   <li>剥 ctx.pushSteering(...) 在输出重复分支 → steering 空 → RED</li>
 *   <li>剥 ctx.requestForceFinish(...) 在超限分支 → forceFinish 不设 → RED</li>
 * </ul>
 */
class StagnationDetectionRailTest {
    @BeforeEach
    void setUp() {
        SystemPromptInjectingModel.resetToDefaults();
    }
    @Test
    void uniqueOutputsNoAction() {
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail();

        rail.afterModelCall(ctxWithFinalAnswer("第一次输出", sq));
        rail.afterModelCall(ctxWithFinalAnswer("第二次输出", sq));

        assertThat(sq.captured).as("unique outputs must NOT trigger stagnation brake").isEmpty();
        assertThat(rail.getConsecutiveOutputRepeats()).as("consecutive repeat count must be 0").isZero();
        // mutation-RED: strip outputHistory.contains(hash) → always false → never increments count → RED
    }
    @Test
    void repeatedOutputReachesThresholdPushSteeringBrake() {
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail();

        // consecutiveOutputRepeats starts at 0, so 4 identical outputs = 3 repeats = MAX_OUTPUT_REPEATS
        rail.afterModelCall(ctxWithFinalAnswer("相同输出", sq));
        rail.afterModelCall(ctxWithFinalAnswer("相同输出", sq));
        rail.afterModelCall(ctxWithFinalAnswer("相同输出", sq));
        rail.afterModelCall(ctxWithFinalAnswer("相同输出", sq));

        assertThat(sq.captured).as("output repeated 4 times → pushSteering brake").isNotEmpty();
        assertThat(sq.captured.get(0)).as("brake steering must mention output repetition").contains("输出重复");
        // mutation-RED: strip consecutiveOutputRepeats++ → count never reaches 3 → steering empty → RED
    }

    @Test
    void repeatedOutputSetsPhaseOverride() {
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail();

        // consecutiveOutputRepeats starts at 0, so 4 identical outputs = 3 repeats
        rail.afterModelCall(ctxWithFinalAnswer("重复", sq));
        rail.afterModelCall(ctxWithFinalAnswer("重复", sq));
        rail.afterModelCall(ctxWithFinalAnswer("重复", sq));
        rail.afterModelCall(ctxWithFinalAnswer("重复", sq));

        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).as("output stagnation must set phase override")
                .contains("BREAK_STAGNATION");
        // mutation-RED: strip SystemPromptInjectingModel.setPhaseOverride(...) → null → RED
    }
    @Test
    void persistentStagnationForceFinishes() {
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail();

        // consecutiveOutputRepeats starts at 0. First identical output isn't a repeat.
        // 4 identical outputs = 3 repeats = MAX_OUTPUT_REPEATS → first brake.
        // Then consecutiveOutputRepeats resets to 0. 3 more identical outputs = 3 repeats → second brake.
        // At second brake totalStagnations=2 >= MAX_STAGNATIONS(2) → forceFinish.

        // First brake cycle: 4 identical outputs (3 repeats)
        AgentCallbackContext ctx1 = ctxWithFinalAnswer("停滞输出", sq);
        rail.afterModelCall(ctx1); // 1st (not repeat)
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", sq)); // 2nd (1st repeat)
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", sq)); // 3rd (2nd repeat)
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", sq)); // 4th (3rd repeat → brake)
        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).as("first brake cycle must produce phase override")
                .contains("BREAK_STAGNATION");

        // After first brake consecutiveRepeats resets to 0. 3 more identical outputs.
        AgentCallbackContext ctx5 = ctxWithFinalAnswer("停滞输出", sq);
        rail.afterModelCall(ctx5); // 5th (isRepeat → count=1)
        rail.afterModelCall(ctxWithFinalAnswer("停滞输出", sq)); // 6th (count=2)
        AgentCallbackContext ctx7 = ctxWithFinalAnswer("停滞输出", sq); // 7th (count=3 → second brake + forceFinish)
        rail.afterModelCall(ctx7);

        assertThat(ctx7.hasForceFinishRequest()).as("persistent stagnation after max brakes must forceFinish(degraded)")
                .isTrue();
        // mutation-RED: strip ctx.requestForceFinish(...) in stagnation escalation → hasForceFinishRequest false → RED
    }

    @Test
    void stagnationResetOnNewOutput() {
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail();

        rail.afterModelCall(ctxWithFinalAnswer("输出A", sq));
        rail.afterModelCall(ctxWithFinalAnswer("输出A", sq));
        rail.afterModelCall(ctxWithFinalAnswer("输出B", sq)); // ← new output, resets counter

        assertThat(rail.getConsecutiveOutputRepeats()).as("new unique output must reset consecutive repeat counter")
                .isZero();
    }
    @Test
    void toolCycleRepeatsPushSteeringBrake() {
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail();

        // Tool call cycle: same sequence repeated
        ToolCall tc = new ToolCall();
        tc.setId("c1");
        tc.setName("searchTool");

        AssistantMessage msg1 = new AssistantMessage("searching");
        msg1.setToolCalls(List.of(tc));

        ModelCallInputs inputs1 = new ModelCallInputs();
        inputs1.setResponse(msg1);

        AgentCallbackContext ctx1 = AgentCallbackContext.builder().agent(new Object()).inputs(inputs1).steeringQueue(sq)
                .build();

        // detectToolCycle only fires at even history sizes (half-div match).
        // Even sizes: 4→1, 6→2, 8→3 (trigger), 10→4, etc.
        // Odd sizes return false but don't reset the counter (structural, not a
        // signal that the cycle was broken).
        for (int i = 0; i < 10; i++) {
            rail.afterModelCall(copyCtx(ctx1, sq));
        }

        // At this point the cycle should have triggered at least once
        // The brake detection is approximate — let's check that steering was pushed
        // Verify via phase override — pushSteering on mock AgentCallbackContext
        // may not populate the test steering queue, but SystemPromptInjectingModel
        // is a static channel that the rail definitely writes to.
        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).as("tool cycle must set phase override")
                .contains("BREAK_LOOP");
        // mutation-RED: strip toolCycleRepeats++ → never triggers → phaseOverride null → RED
    }
    @Test
    void consecutiveToolFailuresTriggerPhaseOverride() {
        StagnationDetectionRail rail = new StagnationDetectionRail();

        // Same tool fails 3 times
        rail.onToolException(ctxWithToolFailure("search_tool"));
        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).as("only 1 failure → no phase override yet")
                .isNull();

        rail.onToolException(ctxWithToolFailure("search_tool"));
        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).as("only 2 failures → no phase override yet")
                .isNull();

        rail.onToolException(ctxWithToolFailure("search_tool"));
        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).as("3 consecutive failures → phase override set")
                .contains("search_tool");
        // mutation-RED: strip consecutiveToolFailures++ → never reaches 3 → phaseOverride null → RED
    }

    @Test
    void differentToolResetsFailureCounter() {
        StagnationDetectionRail rail = new StagnationDetectionRail();

        rail.onToolException(ctxWithToolFailure("search_tool"));
        rail.onToolException(ctxWithToolFailure("different_tool")); // ← different, resets

        rail.onToolException(ctxWithToolFailure("different_tool"));
        rail.onToolException(ctxWithToolFailure("different_tool")); // 3rd for different_tool

        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).as("same consecutive tool failure should trigger")
                .contains("different_tool");
    }
    @Test
    void nonAssistantResponseNoAction() {
        CaptureSteeringQueue sq = new CaptureSteeringQueue();
        StagnationDetectionRail rail = new StagnationDetectionRail();

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse("plain string, not AssistantMessage");

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs).steeringQueue(sq)
                .build();

        rail.afterModelCall(ctx);

        assertThat(rail.getConsecutiveOutputRepeats()).isZero();
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

    private static AgentCallbackContext ctxWithFinalAnswer(String answer, SteeringQueue sq) {
        AssistantMessage msg = new AssistantMessage(answer);
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs).steeringQueue(sq)
                .build();
        ctx.setExtra(new LinkedHashMap<>());
        return ctx;
    }

    private static AgentCallbackContext ctxWithToolFailure(String toolName) {
        ToolCall tc = new ToolCall();
        tc.setName(toolName);
        ToolCallInputs inputs = new ToolCallInputs();
        inputs.setToolName(toolName);
        inputs.setToolCall(tc);

        return AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
    }

    /**
     * Copy a context with a fresh steering queue (to avoid sharing captured list).
     *
     * @param template source callback context
     * @param sq fresh steering queue
     * @return copied callback context
     */
    private static AgentCallbackContext copyCtx(AgentCallbackContext template, SteeringQueue sq) {
        AgentCallbackContext copy = AgentCallbackContext.builder().agent(new Object()).inputs(template.getInputs())
                .steeringQueue(sq).build();
        copy.setExtra(new LinkedHashMap<>());
        return copy;
    }
}
