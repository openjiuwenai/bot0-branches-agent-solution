/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.selfheal;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RootCauseRail 承重测试 — mock context 证双钩子控制流。
 * mutation-RED: strip onToolException mark → afterModelCall no degrade → RED.
 */
class RootCauseRailTest {
    @Test
    void toolExceptionThenAfterModelCallFiresForceFinishDegraded() {
        RootCauseRail rail = new RootCauseRail();
        Map<String, Object> invocationExtra = new LinkedHashMap<>();

        // Simulate tool exception → onToolException marks hasPendingDegrade
        AgentCallbackContext toolExCtx = ctxWithToolName("brokenTool", invocationExtra);
        assertThat(rail.hasPendingDegrade(toolExCtx)).isFalse();
        rail.onToolException(toolExCtx);
        assertThat(rail.hasPendingDegrade(toolExCtx)).as("onToolException must mark hasPendingDegrade").isTrue();
        // mutation-RED: strip hasPendingDegrade=true → hasPendingDegrade false → RED

        // Simulate next afterModelCall → forceFinish(degraded) consumed
        AgentCallbackContext modelCtx = AgentCallbackContext.builder().agent(new Object()).event(null)
                .extra(invocationExtra).build();
        rail.afterModelCall(modelCtx);

        assertThat(modelCtx.hasForceFinishRequest())
                .as("afterModelCall must fire requestForceFinish when hasPendingDegrade").isTrue();
        // mutation-RED: strip afterModelCall forceFinish → hasForceFinishRequest false → RED
    }

    @Test
    void noToolExceptionAfterModelCallDoesNotForceFinish() {
        RootCauseRail rail = new RootCauseRail();
        AgentCallbackContext modelCtx = AgentCallbackContext.builder().agent(new Object()).event(null).build();
        rail.afterModelCall(modelCtx);

        assertThat(modelCtx.hasForceFinishRequest())
                .as("afterModelCall must NOT forceFinish when no prior tool exception").isFalse();
        // mutation-RED: remove hasPendingDegrade guard → forceFinish always fires → RED
    }

    @Test
    void afterDegradePendingClearedNoDoubleFire() {
        RootCauseRail rail = new RootCauseRail();
        Map<String, Object> invocationExtra = new LinkedHashMap<>();
        rail.onToolException(ctxWithToolName("tool1", invocationExtra));

        AgentCallbackContext ctx1 = AgentCallbackContext.builder().agent(new Object()).event(null)
                .extra(invocationExtra).build();
        rail.afterModelCall(ctx1);
        assertThat(ctx1.hasForceFinishRequest()).isTrue();

        // Second afterModelCall should NOT re-fire (hasPendingDegrade cleared)
        AgentCallbackContext ctx2 = AgentCallbackContext.builder().agent(new Object()).event(null)
                .extra(invocationExtra).build();
        rail.afterModelCall(ctx2);
        assertThat(ctx2.hasForceFinishRequest())
                .as("hasPendingDegrade must be cleared after forceFinish — no double fire").isFalse();
    }

    @Test
    void pendingToolFailureDoesNotCrossInvocationContexts() {
        RootCauseRail rail = new RootCauseRail();
        AgentCallbackContext firstInvocation = ctxWithToolName("brokenTool");
        AgentCallbackContext secondInvocation = AgentCallbackContext.builder().agent(new Object()).build();

        rail.onToolException(firstInvocation);
        rail.afterModelCall(secondInvocation);

        assertThat(secondInvocation.hasForceFinishRequest())
                .as("a tool failure from another invocation must not degrade this request").isFalse();
    }

    private static AgentCallbackContext ctxWithToolName(String toolName) {
        return ctxWithToolName(toolName, new LinkedHashMap<>());
    }

    private static AgentCallbackContext ctxWithToolName(String toolName, Map<String, Object> invocationExtra) {
        // Minimal context — no ToolCallInputs (rail falls back to __unknown_tool__).
        // The control-flow test doesn't need a specific tool name.
        return AgentCallbackContext.builder().agent(new Object()).event(null).extra(invocationExtra).build();
    }
}
