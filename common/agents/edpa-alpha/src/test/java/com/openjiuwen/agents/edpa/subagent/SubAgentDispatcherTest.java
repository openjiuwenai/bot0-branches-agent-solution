/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * SubAgentDispatcher + SubAgentTool bearing tests.
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Strip executor.execute() call → result always null → RED</li>
 *   <li>Strip inputParams schema → LLM can't call tool → RED</li>
 * </ul>
 *
 * @since 2026-07
 */
class SubAgentDispatcherTest {
    @Test
    void subAgentTool_invokeDelegatesToExecutor() {
        SubAgentDispatcher.SubAgentExecutor executor = (userInput, subGoal) -> "SubAgent result for: " + subGoal;

        SubAgentDispatcher.SubAgentTool tool = new SubAgentDispatcher.SubAgentTool("research_agent", "研究子智能体",
                executor);

        Map<String, Object> args = Map.of("sub_goal", "分析市场趋势", "user_input", "分析A股");
        Object result = tool.invoke(args, Map.of());

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertThat(resultMap.get("status")).isEqualTo("completed");
        assertThat(resultMap.get("result")).isEqualTo("SubAgent result for: 分析市场趋势");
    }

    @Test
    void subAgentTool_invokeWithNullArgsDoesNotCrash() {
        SubAgentDispatcher.SubAgentExecutor executor = (userInput, subGoal) -> "ok";
        SubAgentDispatcher.SubAgentTool tool = new SubAgentDispatcher.SubAgentTool("test_agent", "test", executor);

        Object result = tool.invoke(null, Map.of());

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertThat(resultMap.get("status")).isEqualTo("completed");
    }

    @Test
    void subAgentTool_cardHasCorrectIdAndName() {
        SubAgentDispatcher.SubAgentTool tool = new SubAgentDispatcher.SubAgentTool("my_agent", "我的子智能体",
                (u, g) -> "ok");

        assertThat(tool.getCard().getId()).as("card id must match tool name").isEqualTo("my_agent");
        assertThat(tool.getCard().getName()).as("card name must match tool name").isEqualTo("my_agent");
        assertThat(tool.getCard().getDescription()).as("card description must be set").isEqualTo("我的子智能体");
    }

    @Test
    void subAgentTool_cardHasInputParamsSchema() {
        SubAgentDispatcher.SubAgentTool tool = new SubAgentDispatcher.SubAgentTool("agent", "test", (u, g) -> "ok");

        assertThat(tool.getCard().getInputParams()).as("card must have inputParams schema for LLM tool-calling")
                .isNotNull().isNotEmpty();
    }

    @Test
    void subAgentTool_streamWrapsInvokeResult() {
        SubAgentDispatcher.SubAgentExecutor executor = (userInput, subGoal) -> "streamed result";
        SubAgentDispatcher.SubAgentTool tool = new SubAgentDispatcher.SubAgentTool("agent", "test", executor);

        var iterator = tool.stream(Map.of("sub_goal", "test"), Map.of());

        assertThat(iterator.hasNext()).isTrue();
        Object first = iterator.next();
        assertThat(first).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) first).get("result")).isEqualTo("streamed result");
        assertThat(iterator.hasNext()).as("single-element iterator must be exhausted").isFalse();
    }

    @Test
    void executorReceivesCorrectArguments() {
        String[] captured = new String[2];
        SubAgentDispatcher.SubAgentExecutor executor = (userInput, subGoal) -> {
            captured[0] = userInput;
            captured[1] = subGoal;
            return "ok";
        };

        SubAgentDispatcher.SubAgentTool tool = new SubAgentDispatcher.SubAgentTool("agent", "test", executor);

        tool.invoke(Map.of("sub_goal", "分析趋势", "user_input", "A股分析"), Map.of());

        assertThat(captured[0]).as("executor must receive user_input").isEqualTo("A股分析");
        assertThat(captured[1]).as("executor must receive sub_goal").isEqualTo("分析趋势");
    }
}
