/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.selfheal;

import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RootCauseRail 承重测试 — mock context 证双钩子控制流。
 * mutation-RED: strip onToolException mark → afterModelCall no degrade → RED.
 */
class RootCauseRailTest {

    @Test
    void toolException_thenAfterModelCall_firesForceFinishDegraded() {
        RootCauseRail rail = new RootCauseRail();
        assertThat(rail.hasPendingDegrade()).isFalse();

        // Simulate tool exception → onToolException marks pendingDegrade
        AgentCallbackContext toolExCtx = ctxWithToolName("brokenTool");
        rail.onToolException(toolExCtx);
        assertThat(rail.hasPendingDegrade())
                .as("onToolException must mark pendingDegrade")
                .isTrue();
        // mutation-RED: strip pendingDegrade=true → hasPendingDegrade false → RED

        // Simulate next afterModelCall → forceFinish(degraded) consumed
        AgentCallbackContext modelCtx = AgentCallbackContext.builder()
                .agent(new Object()).event(null).build();
        rail.afterModelCall(modelCtx);

        assertThat(modelCtx.hasForceFinishRequest())
                .as("afterModelCall must fire requestForceFinish when pendingDegrade")
                .isTrue();
        // mutation-RED: strip afterModelCall forceFinish → hasForceFinishRequest false → RED
    }

    @Test
    void noToolException_afterModelCall_doesNotForceFinish() {
        RootCauseRail rail = new RootCauseRail();
        AgentCallbackContext modelCtx = AgentCallbackContext.builder()
                .agent(new Object()).event(null).build();
        rail.afterModelCall(modelCtx);

        assertThat(modelCtx.hasForceFinishRequest())
                .as("afterModelCall must NOT forceFinish when no prior tool exception")
                .isFalse();
        // mutation-RED: remove pendingDegrade guard → forceFinish always fires → RED
    }

    @Test
    void afterDegrade_pendingCleared_noDoubleFire() {
        RootCauseRail rail = new RootCauseRail();
        rail.onToolException(ctxWithToolName("tool1"));

        AgentCallbackContext ctx1 = AgentCallbackContext.builder()
                .agent(new Object()).event(null).build();
        rail.afterModelCall(ctx1);
        assertThat(ctx1.hasForceFinishRequest()).isTrue();

        // Second afterModelCall should NOT re-fire (pendingDegrade cleared)
        AgentCallbackContext ctx2 = AgentCallbackContext.builder()
                .agent(new Object()).event(null).build();
        rail.afterModelCall(ctx2);
        assertThat(ctx2.hasForceFinishRequest())
                .as("pendingDegrade must be cleared after forceFinish — no double fire")
                .isFalse();
    }


    private static AgentCallbackContext ctxWithToolName(String toolName) {
        // Minimal context — no ToolCallInputs (rail falls back to __unknown_tool__).
        // The control-flow test doesn't need a specific tool name.
        return AgentCallbackContext.builder()
                .agent(new Object())
                .event(null)
                .build();
    }
}