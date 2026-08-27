/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parity with Python {@code test_tool_call_limit_rail.py}.
 *
 * @since 2026-08-27
 */
class ToolCallLimitRailTest {
    private static AgentCallbackContext ctx(List<ToolCall> toolCalls) {
        AssistantMessage response =
                AssistantMessage.builder().toolCalls(toolCalls).build();
        ModelCallInputs inputs = ModelCallInputs.builder().response(response).build();
        return AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new LinkedHashMap<>())
                .build();
    }

    @Test
    void parallelToolCallsCountAsOneRound() {
        ToolCallLimitRail rail = new ToolCallLimitRail(2);
        AgentCallbackContext ctx = ctx(List.of(new ToolCall("1", "function", "a", "{}", 0),
                new ToolCall("2", "function", "b", "{}", 1)));

        rail.afterModelCall(ctx);

        assertThat(ctx.getExtra()).containsEntry(ToolCallLimitRail.COUNT_KEY, 1);
        assertThat(ctx.consumeForceFinish()).isNull();
    }

    @Test
    void finalAnswerDoesNotConsumeRound() {
        ToolCallLimitRail rail = new ToolCallLimitRail(1);
        AgentCallbackContext ctx = ctx(List.of());

        rail.afterModelCall(ctx);

        assertThat(ctx.getExtra()).isEmpty();
        assertThat(ctx.consumeForceFinish()).isNull();
    }

    @Test
    void nextToolRoundIsStoppedBeforeExecution() {
        ToolCallLimitRail rail = new ToolCallLimitRail(1);
        AgentCallbackContext ctx = ctx(List.of(new ToolCall("1", "function", "a", "{}", 0)));

        rail.afterModelCall(ctx);
        rail.afterModelCall(ctx);

        assertThat(ctx.getExtra()).containsEntry(ToolCallLimitRail.COUNT_KEY, 1);
        var finish = ctx.consumeForceFinish();
        assertThat(finish).isNotNull();
        Map<String, Object> result = finish.getResult();
        assertThat(result)
                .containsEntry("output", "Max iterations reached without completion")
                .containsEntry("result_type", "error");
    }
}
