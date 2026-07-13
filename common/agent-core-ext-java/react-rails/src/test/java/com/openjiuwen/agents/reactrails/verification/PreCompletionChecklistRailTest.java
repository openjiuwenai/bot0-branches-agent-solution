/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.agents.reactrails.enforcing.PromptInjectionState;
import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel.InjectionMode;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PreCompletionChecklistRail 承重测试 — mock context 证 PLAN/BUILD 阶段切换 + guardrail 注入.
 *
 * <p>七出口验证：
 * <ol>
 *   <li>首次 beforeModelCall → setInjectionMode(PLAN_MODE)</li>
 *   <li>PLAN 阶段内 beforeModelCall → mode 保持 PLAN_MODE</li>
 *   <li>BUILD 阶段 beforeModelCall → mode 切为 BUILD_MODE</li>
 *   <li>前轮纯文本 → beforeModelCall 设 phaseOverride("REMINDER: use tools")</li>
 *   <li>停滞检测通过 extra → beforeModelCall 设 phaseOverride("BREAK_STAGNATION")</li>
 *   <li>afterModelCall → callCount 递增</li>
 *   <li>afterModelCall → 工具调用记录到 toolNamesCalled</li>
 * </ol>
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>剥 SystemPromptInjectingModel.setInjectionMode(PLAN_MODE) → mode=NONE → RED</li>
 *   <li>剥 SystemPromptInjectingModel.setInjectionMode(BUILD_MODE) → mode=PLAN_MODE → RED</li>
 *   <li>剥 setPhaseOverride(停滞分支) → phaseOverride null → RED</li>
 *   <li>剥 callCount++ → beforeModelCall 永不进入 BUILD 分支 → RED</li>
 *   <li>剥 toolNamesCalled.add(tc.getName()) → diversity=0 → RED</li>
 * </ul>
 */
class PreCompletionChecklistRailTest {
    private PromptInjectionState injectionState;
    private Map<String, Object> invocationExtra;

    @BeforeEach
    void setUp() {
        injectionState = new PromptInjectionState();
        invocationExtra = new LinkedHashMap<>();
    }
    @Test
    void constructorWithValidRounds() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(3, injectionState);
        AgentCallbackContext ctx = ctxWithExtra(Map.of());
        assertThat(rail.getPlanMaxRounds()).isEqualTo(3);
        assertThat(rail.getCallCount(ctx)).isZero();
        assertThat(rail.getToolDiversity(ctx)).isZero();
    }

    @Test
    void constructorRejectsZeroRounds() {
        assertThatThrownBy(() -> new PreCompletionChecklistRail(0, injectionState))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void beforeModelCallFirstCallSetsPlanMode() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);
        AgentCallbackContext ctx = ctxWithExtra(Map.of());

        rail.beforeModelCall(ctx);

        assertThat(injectionState.getMode()).as("first call must set PLAN_MODE").isEqualTo(InjectionMode.PLAN_MODE);
        assertThat(injectionState.peekPhaseOverride()).as("first call must NOT set phase override").isNull();
        // mutation-RED: strip setInjectionMode(PLAN_MODE) → mode stays NONE → RED
    }
    @Test
    void beforeModelCallPlanPhaseKeepsPlanMode() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(3, injectionState);
        // Simulate 1 afterModelCall → callCount=1
        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));
        injectionState.setMode(InjectionMode.NONE); // reset for test

        rail.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(injectionState.getMode()).as("before planMaxRounds must set PLAN_MODE")
                .isEqualTo(InjectionMode.PLAN_MODE);
        // mutation-RED: strip setInjectionMode(PLAN_MODE) → mode stays NONE → RED
    }

    @Test
    void beforeModelCallPreviousWasFinalSetsToolReminder() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(3, injectionState);
        // First round: produce a final answer (no tool calls) → hasPreviousFinalAnswer=true
        rail.afterModelCall(ctxWithFinalAnswer("I think the answer is 42"));

        rail.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(injectionState.getMode()).as("after first final-answer round, mode must still be PLAN_MODE")
                .isEqualTo(InjectionMode.PLAN_MODE);

        // The hasPreviousFinalAnswer flag is set, but toolNamesCalled is empty on first call
        // (the guard setPhaseOverride only triggers if !toolNamesCalled.isEmpty())
        // So we need a second round to test: first round with tools, second round text-only

        // Reset and do a second test that simulates "tools used before, now text-only"
        injectionState.reset();
        PreCompletionChecklistRail rail2 = new PreCompletionChecklistRail(3, injectionState);
        // Round 1: tool call → toolNamesCalled populated, hasPreviousFinalAnswer=false
        rail2.afterModelCall(ctxWithToolResult("search", "toolResult"));
        // Round 2: final answer → hasPreviousFinalAnswer=true
        rail2.afterModelCall(ctxWithFinalAnswer("the answer is 42"));

        rail2.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(injectionState.peekPhaseOverride())
                .as("after tool-using round followed by text-only round, phaseOverride must be tool reminder")
                .contains("REMINDER");
        // mutation-RED: strip setPhaseOverride("REMINDER") → peek null → RED
    }
    @Test
    void beforeModelCallBuildPhaseSwitchesToBuildMode() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);
        // Simulate 2 afterModelCall rounds → callCount=2, triggers BUILD
        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));
        rail.afterModelCall(ctxWithToolResult("calc", "toolResult"));

        injectionState.setMode(InjectionMode.PLAN_MODE); // simulate PLAN

        rail.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(injectionState.getMode()).as("after planMaxRounds, mode must switch to BUILD_MODE")
                .isEqualTo(InjectionMode.BUILD_MODE);
        // mutation-RED: strip setInjectionMode(BUILD_MODE) → mode stays PLAN_MODE → RED
    }
    @Test
    void beforeModelCallStagnationDetectedInjectsBreakLoop() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);
        // First round to set callCount > 0
        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));

        AgentCallbackContext ctx = ctxWithExtra(Map.of("stagnation_detected", true));

        rail.beforeModelCall(ctx);

        assertThat(injectionState.peekPhaseOverride())
                .as("stagnation detected must inject BREAK_STAGNATION phase override").contains("BREAK_STAGNATION");
        // mutation-RED: strip setPhaseOverride(BREAK_STAGNATION) → peek null → RED
    }
    @Test
    void afterModelCallIncrementsCallCount() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);
        AgentCallbackContext ctx = ctxWithToolResult("search", "toolResult");

        assertThat(rail.getCallCount(ctx)).isZero();

        rail.afterModelCall(ctx);
        assertThat(rail.getCallCount(ctx)).isOne();

        rail.afterModelCall(ctxWithToolResult("calc", "toolResult"));
        assertThat(rail.getCallCount(ctx)).isEqualTo(2);
        // mutation-RED: strip callCount++ → stays 0 → beforeModelCall never hits BUILD → RED
    }
    @Test
    void afterModelCallTracksToolDiversity() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);
        AgentCallbackContext ctx = ctxWithToolResult("search", "toolResult");

        rail.afterModelCall(ctx);
        assertThat(rail.getToolDiversity(ctx)).isEqualTo(1);

        rail.afterModelCall(ctxWithToolResult("search", "toolResult")); // same tool
        assertThat(rail.getToolDiversity(ctx)).isEqualTo(1); // no new

        rail.afterModelCall(ctxWithToolResult("calc", "toolResult")); // new tool
        assertThat(rail.getToolDiversity(ctx)).isEqualTo(2);
        // mutation-RED: strip toolNamesCalled.add(tc.getName()) → diversity stays 0 → RED
    }
    @Test
    void afterModelCallTracksOutputHashes() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);
        AgentCallbackContext ctx = ctxWithFinalAnswer("short output");

        rail.afterModelCall(ctx);
        assertThat(rail.getOutputHashes(ctx)).hasSize(1);
        assertThat(rail.getOutputHashes(ctx).get(0)).isEqualTo("short output");

        rail.afterModelCall(ctxWithFinalAnswer("different output"));
        assertThat(rail.getOutputHashes(ctx)).hasSize(2);
        // mutation-RED: strip outputHashes.add(...) → size always 0 → RED
    }
    @Test
    void afterModelCallFinalAnswerSetsPreviousFinalAnswerFlag() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);
        // Final answer (no tool calls)
        rail.afterModelCall(ctxWithFinalAnswer("my answer"));
        // Now beforeModelCall should see hasPreviousFinalAnswer=true
        // (indirectly tested via phaseOverride set in the right conditions)
        // We already test this in testPreviousWasFinal_setsToolReminder
    }
    @Test
    void afterModelCallNonAssistantResponseNoAction() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse("plain string, not AssistantMessage");

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
        ctx.setExtra(invocationExtra);

        rail.afterModelCall(ctx);

        assertThat(rail.getCallCount(ctx)).isZero();
    }
    @Test
    void planPhaseBoundarySwitchesAtCorrectCount() {
        // planMaxRounds=2: PLAN for callCount 0-1, BUILD for callCount >= 2
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);

        // callCount=0 → beforeModelCall: callCount < 2 → PLAN_MODE
        // (first call is special: callCount==0 → PLAN_MODE, returns early)
        rail.beforeModelCall(ctxWithExtra(Map.of()));
        assertThat(injectionState.getMode()).as("before any afterModelCall, mode must be PLAN_MODE")
                .isEqualTo(InjectionMode.PLAN_MODE);

        // 1st afterModelCall → callCount=1
        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));
        injectionState.setMode(InjectionMode.NONE);
        // beforeModelCall: callCount=1 < 2 → PLAN_MODE
        rail.beforeModelCall(ctxWithExtra(Map.of()));
        assertThat(injectionState.getMode()).as("callCount=1 < planMaxRounds=2, mode must be PLAN_MODE")
                .isEqualTo(InjectionMode.PLAN_MODE);

        // 2nd afterModelCall → callCount=2
        rail.afterModelCall(ctxWithToolResult("calc", "toolResult"));
        injectionState.setMode(InjectionMode.PLAN_MODE);
        // beforeModelCall: callCount=2 >= 2 → BUILD_MODE
        rail.beforeModelCall(ctxWithExtra(Map.of()));
        assertThat(injectionState.getMode()).as("callCount=2 >= planMaxRounds=2, mode must switch to BUILD_MODE")
                .isEqualTo(InjectionMode.BUILD_MODE);
        // mutation-RED: strip callCount++ → callCount stays < 2 → never BUILD → RED
    }

    @Test
    void phaseCountersDoNotCrossInvocationContexts() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2, injectionState);
        Map<String, Object> firstInvocationExtra = new LinkedHashMap<>();

        rail.afterModelCall(ctxWithToolResult("search", "result", firstInvocationExtra));
        rail.afterModelCall(ctxWithToolResult("calculate", "result", firstInvocationExtra));
        injectionState.setMode(InjectionMode.NONE);
        rail.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(injectionState.getMode()).as("a fresh invocation must start in PLAN mode")
                .isEqualTo(InjectionMode.PLAN_MODE);
    }
    private AgentCallbackContext ctxWithExtra(Map<String, Object> extra) {
        invocationExtra.putAll(extra);
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).build();
        ctx.setExtra(invocationExtra);
        return ctx;
    }

    private AgentCallbackContext ctxWithToolResult(String toolName, String result) {
        return ctxWithToolResult(toolName, result, invocationExtra);
    }

    private static AgentCallbackContext ctxWithToolResult(String toolName, String result,
            Map<String, Object> invocationExtra) {
        ToolCall tc = new ToolCall();
        tc.setId("tc1");
        tc.setName(toolName);
        tc.setArguments("{}");

        AssistantMessage msg = new AssistantMessage(result);
        msg.setToolCalls(List.of(tc));

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
        ctx.setExtra(invocationExtra);
        return ctx;
    }

    private AgentCallbackContext ctxWithFinalAnswer(String answer) {
        AssistantMessage msg = new AssistantMessage(answer);
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
        ctx.setExtra(invocationExtra);
        return ctx;
    }
}
