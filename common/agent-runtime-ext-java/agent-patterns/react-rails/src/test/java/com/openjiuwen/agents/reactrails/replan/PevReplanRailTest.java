/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.SteeringQueue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PevReplanRail 承重测试 — mock context 证 PEV dispatch 控制流:
 * replan 计数 + 超限 escalate + GlobalReplan→pushSteering + AcceptPartial→forceFinish.
 * <p>mutation-RED (剥 token → RED):
 * <ol>
 *   <li>{@code replanCount++} 剥 → 永不超限 → test {@link #overLimit_escalatesForceFinish()} RED</li>
 *   <li>{@code ctx.pushSteering(...)} 从 GlobalReplan 分支剥 → {@link #globalReplan_pushesSteering()} RED</li>
 *   <li>{@code ctx.requestForceFinish(degradedResult(...))} 从 AcceptPartial 分支剥 → {@link #acceptPartial_forceFinishes()} RED</li>
 *   <li>{@code PevKernel.toReplanAction} 调用剥 → dispatch 不执行 → {@link #globalReplan_pushesSteering()} RED</li>
 * </ol>
 */
class PevReplanRailTest {
    @Test
    void underLimitReplanAllowedNoForceFinish() {
        PevReplanRail rail = new PevReplanRail(2);

        rail.afterModelCall(ctxWithReplanToolCall("first replan", ""));

        assertThat(rail.replanCount()).isEqualTo(1);
        // mutation-RED: strip replanCount++ → count always 0 → RED
    }

    @Test
    void atLimitStillAllowedNoForceFinish() {
        PevReplanRail rail = new PevReplanRail(2);

        rail.afterModelCall(ctxWithReplanToolCall("first replan", "")); // count=1
        rail.afterModelCall(ctxWithReplanToolCall("second replan", "")); // count=2 (==max)

        assertThat(rail.replanCount()).isEqualTo(2);
        // IFF: count==max → allow (not escalate). Only count>max → escalate.
    }

    @Test
    void overLimitEscalatesForceFinish() {
        PevReplanRail rail = new PevReplanRail(2);

        rail.afterModelCall(ctxWithReplanToolCall("r1", "")); // count=1
        rail.afterModelCall(ctxWithReplanToolCall("r2", "")); // count=2
        AgentCallbackContext ctx3 = ctxWithReplanToolCall("r3", "");
        rail.afterModelCall(ctx3); // count=3 (>max) → forceFinish

        assertThat(rail.replanCount()).isEqualTo(3);
        assertThat(ctx3.hasForceFinishRequest()).as("count>max must fire requestForceFinish(degraded)").isTrue();
        // mutation-RED: strip replanCount++ → count never >max → false → RED
    }

    @Test
    void noReplanToolCallNoCounting() {
        PevReplanRail rail = new PevReplanRail(2);

        rail.afterModelCall(ctxWithNormalAnswer());

        assertThat(rail.replanCount()).isEqualTo(0);
    }

    @Test
    void noToolCallsNoCounting() {
        PevReplanRail rail = new PevReplanRail(2);

        // AssistantMessage with null toolCalls (final answer)
        rail.afterModelCall(ctxWithNoToolCalls());

        assertThat(rail.replanCount()).isEqualTo(0);
    }
    @Test
    void globalReplanPushesSteering() {
        PevReplanRail rail = new PevReplanRail(5);
        TestSteeringQueue sq = new TestSteeringQueue();

        AgentCallbackContext ctx = ctxWithSteeringQueue(ctxWithReplanToolCall("方向错误，需要重新规划", "先调研再执行"), sq);
        rail.afterModelCall(ctx);

        // GlobalReplan: pushSteering called, no forceFinish
        assertThat(ctx.hasForceFinishRequest())
                .as("GlobalReplan must NOT forceFinish — steering survives for next iteration").isFalse();
        assertThat(sq.steered).as("GlobalReplan must push steering text to SteeringQueue").isNotEmpty();
        assertThat(sq.steered.get(0)).contains("【全局重规划】").contains("方向错误");
        // mutation-RED: strip ctx.pushSteering(...) in GlobalReplan branch → sq.steered empty → RED
    }
    @Test
    void acceptPartialForceFinishes() {
        PevReplanRail rail = new PevReplanRail(5);
        TestSteeringQueue sq = new TestSteeringQueue();

        // Pre-condition: record a tool failure
        rail.recordToolFailure("search_tool");

        // LLM calls __replan__ with reason mentioning the failed tool
        AgentCallbackContext ctx = ctxWithSteeringQueue(ctxWithReplanToolCall("search_tool failed, need to replan", ""),
                sq);
        rail.afterModelCall(ctx);

        // DeviceFailure → AcceptPartial → forceFinish, no steering
        assertThat(ctx.hasForceFinishRequest()).as("DeviceFailure → AcceptPartial must forceFinish").isTrue();
        assertThat(sq.steered).as("AcceptPartial must NOT push steering — no more iterations").isEmpty();
        // mutation-RED: strip ctx.requestForceFinish(...) in AcceptPartial branch → false → RED
    }
    @Test
    void recordToolFailureAccumulates() {
        PevReplanRail rail = new PevReplanRail(2);

        rail.recordToolFailure("tool_a");
        rail.recordToolFailure("tool_b");

        assertThat(rail.recentToolFailureNodes()).containsExactly("tool_a", "tool_b");
    }

    @Test
    void diagnosePlanOrAnswerErrorWhenNoToolFailureMatch() {
        PevReplanRail rail = new PevReplanRail(5);
        rail.recordToolFailure("search_tool");

        // LLM calls __replan__ but reason does NOT mention search_tool
        AgentCallbackContext ctx = ctxWithSteeringQueue(ctxWithReplanToolCall("策略需要调整", "新方法"),
                new TestSteeringQueue());
        rail.afterModelCall(ctx);

        // PlanOrAnswerError → GlobalReplan → pushSteering, no forceFinish
        assertThat(ctx.hasForceFinishRequest())
                .as("No tool failure correlation → PlanOrAnswerError → GlobalReplan → no forceFinish").isFalse();
    }
    private static AgentCallbackContext ctxWithReplanToolCall(String reason, String newApproach) {
        ToolCall replanCall = new ToolCall();
        replanCall.setId("call-1");
        replanCall.setName(ReplanTool.TOOL_NAME);
        replanCall.setArguments("{\"" + ReplanTool.ARG_REPLAN_REASON + "\":\"" + reason + "\",\""
                + ReplanTool.ARG_NEW_APPROACH + "\":\"" + newApproach + "\"}");

        AssistantMessage msg = new AssistantMessage();
        msg.setToolCalls(List.of(replanCall));

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        return AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs).build();
    }

    private static AgentCallbackContext ctxWithNormalAnswer() {
        AssistantMessage msg = new AssistantMessage("final answer, no tools");

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        return AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs).build();
    }

    private static AgentCallbackContext ctxWithNoToolCalls() {
        AssistantMessage msg = new AssistantMessage();
        // toolCalls defaults to null → isFinalAnswer

        ModelCallInputs inputs = new ModelCallInputs();
        inputs.setResponse(msg);

        return AgentCallbackContext.builder().agent(new Object()).event(null).inputs(inputs).build();
    }

    private static AgentCallbackContext ctxWithSteeringQueue(AgentCallbackContext ctx, SteeringQueue sq) {
        ctx.setSteeringQueue(sq);
        return ctx;
    }

    /**
     * Simple test stub for {@link SteeringQueue}.
     */
    static class TestSteeringQueue implements SteeringQueue {
        final List<String> steered = new ArrayList<>();

        @Override
        public void pushSteering(String s) {
            steered.add(s);
        }

        @Override
        public List<String> drainSteering() {
            List<String> result = List.copyOf(steered);
            steered.clear();
            return result;
        }
    }
}
