/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.subagent;

/**
 * Functional interface for subagent execution logic.
 *
 * <p>Implementations may:
 * <ul>
 *   <li>Invoke another agent directly (in-process, shared context)</li>
 *   <li>Spawn a subagent via {@code SpawnManager} (isolated context, deferred)</li>
 *   <li>Call an LLM with a subagent-specific prompt</li>
 * </ul>
 *
 * @since 2026-07
 */
@FunctionalInterface
public interface SubAgentExecutor {
    /**
     * Execute the subagent task.
     *
     * @param userInput the original user input (for context)
     * @param subGoal the sub-goal assigned to this subagent
     * @return the subagent's result text
     */
    String execute(String userInput, String subGoal);
}
