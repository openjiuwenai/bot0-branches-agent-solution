/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.subagent;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tool that wraps a {@link SubAgentExecutor} for LLM-callable dispatch.
 *
 * <p>Registration contract mirrors {@code ReplanTool}: dual registration
 * (AbilityManager.add(card) + Runner.resourceMgr().addTool(tool)).
 *
 * <p><b>user_input resolution (P0 fix)</b>: the LLM-visible schema only declares
 * {@code sub_goal} (the LLM cannot know the original user input). The original
 * input is resolved from a {@link Supplier} (typically wired from
 * {@code UserInputCaptureRail}'s shared reference); when no supplier is provided,
 * falls back to using {@code sub_goal} as the context (honest approximation —
 * the sub-goal is the most specific context available).
 *
 * @since 2026-07
 */
public class SubAgentTool extends Tool {
    private final SubAgentExecutor executor;
    private final ToolCard card;
    private final Supplier<String> userInputSupplier;

    /**
     * Constructs the subagent tool with a user-input supplier.
     *
     * @param name tool name (also used as id)
     * @param description tool description for the LLM
     * @param executor subagent execution logic
     * @param userInputSupplier supplies the original user input (nullable — falls back to sub_goal)
     */
    public SubAgentTool(String name, String description, SubAgentExecutor executor,
            Supplier<String> userInputSupplier) {
        super(ToolCard.builder().id(name).name(name).description(description)
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("sub_goal", Map.of("type", "string", "description", "子任务目标")),
                        "required", List.of("sub_goal")))
                .build());
        this.executor = executor;
        this.card = super.getCard();
        this.userInputSupplier = userInputSupplier;
    }

    /**
     * Constructs the subagent tool without a supplier (sub_goal fallback).
     *
     * @param name tool name
     * @param description tool description for the LLM
     * @param executor subagent execution logic
     */
    public SubAgentTool(String name, String description, SubAgentExecutor executor) {
        this(name, description, executor, null);
    }

    @Override
    public ToolCard getCard() {
        return card;
    }

    @Override
    public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
        String subGoal = args != null ? String.valueOf(args.getOrDefault("sub_goal", "")) : "";
        String userInput = resolveUserInput(subGoal);
        String result = executor.execute(userInput, subGoal);
        return Map.of("status", "completed", "result", result);
    }

    @Override
    public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
        return List.<Object>of(invoke(args, kwargs)).iterator();
    }

    /**
     * Resolves the original user input: supplier (if wired) → sub_goal (fallback).
     *
     * @param subGoal the sub-goal (fallback context)
     * @return the resolved user input
     */
    private String resolveUserInput(String subGoal) {
        if (userInputSupplier != null) {
            String supplied = userInputSupplier.get();
            if (supplied != null && !supplied.isBlank()) {
                return supplied;
            }
        }
        // P0 fix: previously always "" because LLM schema has no user_input param.
        // Fallback to sub_goal — honest approximation of the available context.
        return subGoal;
    }
}
