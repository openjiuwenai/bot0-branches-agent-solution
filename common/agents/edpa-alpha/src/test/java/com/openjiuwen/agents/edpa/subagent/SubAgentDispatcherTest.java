/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.subagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * SubAgentDispatcher + SubAgentTool bearing tests (D3: now top-level types).
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
        SubAgentExecutor executor = (userInput, subGoal) -> "SubAgent result for: " + subGoal;

        SubAgentTool tool = new SubAgentTool("research_agent", "研究子智能体", executor);

        Map<String, Object> args = Map.of("sub_goal", "分析市场趋势", "user_input", "分析A股");
        Object result = tool.invoke(args, Map.of());

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertThat(resultMap.get("status")).isEqualTo("completed");
        assertThat(resultMap.get("result")).isEqualTo("SubAgent result for: 分析市场趋势");
    }

    @Test
    void subAgentTool_invokeWithNullArgsDoesNotCrash() {
        SubAgentExecutor executor = (userInput, subGoal) -> "ok";
        SubAgentTool tool = new SubAgentTool("test_agent", "test", executor);

        Object result = tool.invoke(null, Map.of());

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertThat(resultMap.get("status")).isEqualTo("completed");
    }

    @Test
    void subAgentTool_cardHasCorrectIdAndName() {
        SubAgentTool tool = new SubAgentTool("my_agent", "我的子智能体", (u, g) -> "ok");

        assertThat(tool.getCard().getId()).as("card id must match tool name").isEqualTo("my_agent");
        assertThat(tool.getCard().getName()).as("card name must match tool name").isEqualTo("my_agent");
        assertThat(tool.getCard().getDescription()).as("card description must be set").isEqualTo("我的子智能体");
    }

    @Test
    void subAgentTool_cardHasInputParamsSchema() {
        SubAgentTool tool = new SubAgentTool("agent", "test", (u, g) -> "ok");

        assertThat(tool.getCard().getInputParams()).as("card must have inputParams schema for LLM tool-calling")
                .isNotNull().isNotEmpty();
    }

    @Test
    void subAgentTool_streamWrapsInvokeResult() {
        SubAgentExecutor executor = (userInput, subGoal) -> "streamed result";
        SubAgentTool tool = new SubAgentTool("agent", "test", executor);

        var iterator = tool.stream(Map.of("sub_goal", "test"), Map.of());

        assertThat(iterator.hasNext()).isTrue();
        Object first = iterator.next();
        assertThat(first).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) first).get("result")).isEqualTo("streamed result");
        assertThat(iterator.hasNext()).as("single-element iterator must be exhausted").isFalse();
    }

    @Test
    void executorReceivesCorrectArguments() {
        // P0 fix: user_input is resolved from supplier (or sub_goal fallback), not from LLM args
        // (the LLM schema only declares sub_goal — LLM cannot provide user_input).
        String[] captured = new String[2];
        SubAgentExecutor executor = (userInput, subGoal) -> {
            captured[0] = userInput;
            captured[1] = subGoal;
            return "ok";
        };

        SubAgentTool tool = new SubAgentTool("agent", "test", executor, () -> "A股分析");

        tool.invoke(Map.of("sub_goal", "分析趋势"), Map.of());

        assertThat(captured[0]).as("user_input must come from supplier (P0 fix)")
                .isEqualTo("A股分析");
        assertThat(captured[1]).as("sub_goal must come from LLM args").isEqualTo("分析趋势");
    }

    @Test
    void userInputSupplierResolvesOriginalInput() {
        // P0 fix: supplier provides the original user input (from UserInputCaptureRail).
        String[] captured = new String[2];
        SubAgentExecutor executor = (userInput, subGoal) -> {
            captured[0] = userInput;
            captured[1] = subGoal;
            return "ok";
        };
        SubAgentTool tool = new SubAgentTool("agent", "test", executor, () -> "原始用户请求");

        // LLM schema only has sub_goal — user_input is resolved from supplier, not from LLM args.
        tool.invoke(Map.of("sub_goal", "子任务"), Map.of());

        assertThat(captured[0]).as("user_input must come from supplier (P0 fix)")
                .isEqualTo("原始用户请求");
        assertThat(captured[1]).as("sub_goal must come from LLM args").isEqualTo("子任务");
    }

    @Test
    void userInputFallsBackToSubGoalWhenNoSupplier() {
        // P0 fix: without supplier, user_input falls back to sub_goal (honest approximation).
        String[] captured = new String[2];
        SubAgentExecutor executor = (userInput, subGoal) -> {
            captured[0] = userInput;
            captured[1] = subGoal;
            return "ok";
        };
        SubAgentTool tool = new SubAgentTool("agent", "test", executor);

        tool.invoke(Map.of("sub_goal", "唯一的上下文"), Map.of());

        assertThat(captured[0]).as("user_input must fall back to sub_goal when no supplier")
                .isEqualTo("唯一的上下文");
        assertThat(captured[1]).isEqualTo("唯一的上下文");
    }
}
