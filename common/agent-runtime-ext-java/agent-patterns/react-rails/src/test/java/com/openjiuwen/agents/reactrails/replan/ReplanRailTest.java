/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReplanRail 承重测试 — mock context 证 replan 计数 + 超限 escalate 控制流。
 * mutation-RED: strip replanCount++ → count永远 0 →永不 forceFinish → RED。
 */
class ReplanRailTest {
    @Test
    void underLimitReplanAllowedNoForceFinish() {
        ReplanRail rail = new ReplanRail(2);

        // First __replan__ call (count=1, under limit=2)
        rail.afterModelCall(ctxWithReplanToolCall());

        assertThat(rail.replanCount()).isEqualTo(1);
        // mutation-RED: strip replanCount++ → count永远 0 → RED
    }

    @Test
    void atLimitStillAllowedNoForceFinish() {
        ReplanRail rail = new ReplanRail(2);

        rail.afterModelCall(ctxWithReplanToolCall()); // count=1
        rail.afterModelCall(ctxWithReplanToolCall()); // count=2 (== max, still allowed — > is the trigger)

        assertThat(rail.replanCount()).isEqualTo(2);
        // IFF: count==max → allow (not escalate). Only count>max → escalate.
    }

    @Test
    void overLimitEscalatesForceFinish() {
        ReplanRail rail = new ReplanRail(2);

        rail.afterModelCall(ctxWithReplanToolCall());
        rail.afterModelCall(ctxWithReplanToolCall());
        AgentCallbackContext ctx3 = ctxWithReplanToolCall();
        rail.afterModelCall(ctx3);

        assertThat(rail.replanCount()).isEqualTo(3);
        assertThat(ctx3.hasForceFinishRequest()).as("count>max must fire requestForceFinish(degraded)").isTrue();
        // mutation-RED: strip forceFinish degraded call → hasForceFinishRequest false → RED
    }

    @Test
    void noReplanToolCallNoCounting() {
        ReplanRail rail = new ReplanRail(2);

        rail.afterModelCall(ctxWithNormalAnswer()); // final answer, no tool calls

        assertThat(rail.replanCount()).isEqualTo(0);
    }

    private static AgentCallbackContext ctxWithReplanToolCall() {
        ToolCall replanCall = new ToolCall();
        replanCall.setId("call-1");
        replanCall.setName(ReplanTool.TOOL_NAME);
        replanCall.setArguments("{\"replan_reason\":\"wrong direction\"}");

        AssistantMessage msg = new AssistantMessage();
        msg.setToolCalls(List.of(replanCall));

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        return AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs).build();
    }

    private static AgentCallbackContext ctxWithNormalAnswer() {
        AssistantMessage msg = new AssistantMessage("final answer, no tools");
        // toolCalls defaults to null/empty → isFinalAnswer

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        return AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs).build();
    }
}
