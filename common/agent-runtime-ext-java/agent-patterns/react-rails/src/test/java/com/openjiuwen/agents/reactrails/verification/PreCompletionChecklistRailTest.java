/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel;
import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel.InjectionMode;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @BeforeEach
    void setUp() {
        SystemPromptInjectingModel.resetToDefaults();
    }
    @Test
    void constructorWithValidRounds() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(3);
        assertThat(rail.getPlanMaxRounds()).isEqualTo(3);
        assertThat(rail.getCallCount()).isZero();
        assertThat(rail.getToolDiversity()).isZero();
    }

    @Test
    void constructorRejectsZeroRounds() {
        assertThatThrownBy(() -> new PreCompletionChecklistRail(0)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void beforeModelCallFirstCallSetsPlanMode() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);
        AgentCallbackContext ctx = ctxWithExtra(Map.of());

        rail.beforeModelCall(ctx);

        assertThat(SystemPromptInjectingModel.getInjectionMode()).as("first call must set PLAN_MODE")
                .isEqualTo(InjectionMode.PLAN_MODE);
        assertThat(SystemPromptInjectingModel.peekPhaseOverride()).as("first call must NOT set phase override")
                .isNull();
        // mutation-RED: strip setInjectionMode(PLAN_MODE) → mode stays NONE → RED
    }
    @Test
    void beforeModelCallPlanPhaseKeepsPlanMode() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(3);
        // Simulate 1 afterModelCall → callCount=1
        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));
        SystemPromptInjectingModel.setInjectionMode(InjectionMode.NONE); // reset for test

        rail.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(SystemPromptInjectingModel.getInjectionMode()).as("before planMaxRounds must set PLAN_MODE")
                .isEqualTo(InjectionMode.PLAN_MODE);
        // mutation-RED: strip setInjectionMode(PLAN_MODE) → mode stays NONE → RED
    }

    @Test
    void beforeModelCallPreviousWasFinalSetsToolReminder() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(3);
        // First round: produce a final answer (no tool calls) → hasPreviousFinalAnswer=true
        rail.afterModelCall(ctxWithFinalAnswer("I think the answer is 42"));

        rail.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(SystemPromptInjectingModel.getInjectionMode())
                .as("after first final-answer round, mode must still be PLAN_MODE").isEqualTo(InjectionMode.PLAN_MODE);

        // The hasPreviousFinalAnswer flag is set, but toolNamesCalled is empty on first call
        // (the guard setPhaseOverride only triggers if !toolNamesCalled.isEmpty())
        // So we need a second round to test: first round with tools, second round text-only

        // Reset and do a second test that simulates "tools used before, now text-only"
        SystemPromptInjectingModel.resetToDefaults();
        PreCompletionChecklistRail rail2 = new PreCompletionChecklistRail(3);
        // Round 1: tool call → toolNamesCalled populated, hasPreviousFinalAnswer=false
        rail2.afterModelCall(ctxWithToolResult("search", "toolResult"));
        // Round 2: final answer → hasPreviousFinalAnswer=true
        rail2.afterModelCall(ctxWithFinalAnswer("the answer is 42"));

        rail2.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(SystemPromptInjectingModel.peekPhaseOverride())
                .as("after tool-using round followed by text-only round, phaseOverride must be tool reminder")
                .contains("REMINDER");
        // mutation-RED: strip setPhaseOverride("REMINDER") → peek null → RED
    }
    @Test
    void beforeModelCallBuildPhaseSwitchesToBuildMode() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);
        // Simulate 2 afterModelCall rounds → callCount=2, triggers BUILD
        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));
        rail.afterModelCall(ctxWithToolResult("calc", "toolResult"));

        SystemPromptInjectingModel.setInjectionMode(InjectionMode.PLAN_MODE); // simulate PLAN

        rail.beforeModelCall(ctxWithExtra(Map.of()));

        assertThat(SystemPromptInjectingModel.getInjectionMode())
                .as("after planMaxRounds, mode must switch to BUILD_MODE").isEqualTo(InjectionMode.BUILD_MODE);
        // mutation-RED: strip setInjectionMode(BUILD_MODE) → mode stays PLAN_MODE → RED
    }
    @Test
    void beforeModelCallStagnationDetectedInjectsBreakLoop() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);
        // First round to set callCount > 0
        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));

        AgentCallbackContext ctx = ctxWithExtra(Map.of("stagnation_detected", true));

        rail.beforeModelCall(ctx);

        assertThat(SystemPromptInjectingModel.peekPhaseOverride())
                .as("stagnation detected must inject BREAK_STAGNATION phase override").contains("BREAK_STAGNATION");
        // mutation-RED: strip setPhaseOverride(BREAK_STAGNATION) → peek null → RED
    }
    @Test
    void afterModelCallIncrementsCallCount() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);

        assertThat(rail.getCallCount()).isZero();

        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));
        assertThat(rail.getCallCount()).isOne();

        rail.afterModelCall(ctxWithToolResult("calc", "toolResult"));
        assertThat(rail.getCallCount()).isEqualTo(2);
        // mutation-RED: strip callCount++ → stays 0 → beforeModelCall never hits BUILD → RED
    }
    @Test
    void afterModelCallTracksToolDiversity() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);

        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));
        assertThat(rail.getToolDiversity()).isEqualTo(1);

        rail.afterModelCall(ctxWithToolResult("search", "toolResult")); // same tool
        assertThat(rail.getToolDiversity()).isEqualTo(1); // no new

        rail.afterModelCall(ctxWithToolResult("calc", "toolResult")); // new tool
        assertThat(rail.getToolDiversity()).isEqualTo(2);
        // mutation-RED: strip toolNamesCalled.add(tc.getName()) → diversity stays 0 → RED
    }
    @Test
    void afterModelCallTracksOutputHashes() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);

        rail.afterModelCall(ctxWithFinalAnswer("short output"));
        assertThat(rail.getOutputHashes()).hasSize(1);
        assertThat(rail.getOutputHashes().get(0)).isEqualTo("short output");

        rail.afterModelCall(ctxWithFinalAnswer("different output"));
        assertThat(rail.getOutputHashes()).hasSize(2);
        // mutation-RED: strip outputHashes.add(...) → size always 0 → RED
    }
    @Test
    void afterModelCallFinalAnswerSetsPreviousFinalAnswerFlag() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);
        // Final answer (no tool calls)
        rail.afterModelCall(ctxWithFinalAnswer("my answer"));
        // Now beforeModelCall should see hasPreviousFinalAnswer=true
        // (indirectly tested via phaseOverride set in the right conditions)
        // We already test this in testPreviousWasFinal_setsToolReminder
    }
    @Test
    void afterModelCallNonAssistantResponseNoAction() {
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse("plain string, not AssistantMessage");

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
        ctx.setExtra(new LinkedHashMap<>());

        rail.afterModelCall(ctx);

        assertThat(rail.getCallCount()).isZero();
    }
    @Test
    void planPhaseBoundarySwitchesAtCorrectCount() {
        // planMaxRounds=2: PLAN for callCount 0-1, BUILD for callCount >= 2
        PreCompletionChecklistRail rail = new PreCompletionChecklistRail(2);

        // callCount=0 → beforeModelCall: callCount < 2 → PLAN_MODE
        // (first call is special: callCount==0 → PLAN_MODE, returns early)
        rail.beforeModelCall(ctxWithExtra(Map.of()));
        assertThat(SystemPromptInjectingModel.getInjectionMode())
                .as("before any afterModelCall, mode must be PLAN_MODE").isEqualTo(InjectionMode.PLAN_MODE);

        // 1st afterModelCall → callCount=1
        rail.afterModelCall(ctxWithToolResult("search", "toolResult"));
        SystemPromptInjectingModel.setInjectionMode(InjectionMode.NONE);
        // beforeModelCall: callCount=1 < 2 → PLAN_MODE
        rail.beforeModelCall(ctxWithExtra(Map.of()));
        assertThat(SystemPromptInjectingModel.getInjectionMode())
                .as("callCount=1 < planMaxRounds=2, mode must be PLAN_MODE").isEqualTo(InjectionMode.PLAN_MODE);

        // 2nd afterModelCall → callCount=2
        rail.afterModelCall(ctxWithToolResult("calc", "toolResult"));
        SystemPromptInjectingModel.setInjectionMode(InjectionMode.PLAN_MODE);
        // beforeModelCall: callCount=2 >= 2 → BUILD_MODE
        rail.beforeModelCall(ctxWithExtra(Map.of()));
        assertThat(SystemPromptInjectingModel.getInjectionMode())
                .as("callCount=2 >= planMaxRounds=2, mode must switch to BUILD_MODE")
                .isEqualTo(InjectionMode.BUILD_MODE);
        // mutation-RED: strip callCount++ → callCount stays < 2 → never BUILD → RED
    }
    private static AgentCallbackContext ctxWithExtra(Map<String, Object> extra) {
        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).build();
        ctx.setExtra(new LinkedHashMap<>(extra));
        return ctx;
    }

    private static AgentCallbackContext ctxWithToolResult(String toolName, String result) {
        ToolCall tc = new ToolCall();
        tc.setId("tc1");
        tc.setName(toolName);
        tc.setArguments("{}");

        AssistantMessage msg = new AssistantMessage(result);
        msg.setToolCalls(List.of(tc));

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
        ctx.setExtra(new LinkedHashMap<>());
        return ctx;
    }

    private static AgentCallbackContext ctxWithFinalAnswer(String answer) {
        AssistantMessage msg = new AssistantMessage(answer);
        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        AgentCallbackContext ctx = AgentCallbackContext.builder().agent(new Object()).inputs(inputs).build();
        ctx.setExtra(new LinkedHashMap<>());
        return ctx;
    }
}
