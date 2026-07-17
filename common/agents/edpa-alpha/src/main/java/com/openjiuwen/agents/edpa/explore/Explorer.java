/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

/**
 * Explore phase SPI — active information gathering before planning.
 *
 * <p>EDPA (Explore→Decision→Plan→Action) adds an explicit Explore phase on top of
 * PEV's sealed dispatch kernel and react-rails' steerable rails. The Explorer is
 * the only new SPI — it gathers information actively (not passively observing)
 * before the Plan phase begins.
 *
 * <p>Implementations may use LLM prompts, tool calls, subagent dispatch, or any
 * combination. The default {@link LlmExplorer} uses LLM-driven exploration.
 *
 * @since 2026-07
 */
public interface Explorer {
    /**
     * Explore the problem space for the given user input.
     *
     * @param userInput the user's original task or question
     * @param budget exploration budget (max rounds, max subagents, timeout)
     * @return structured findings and candidate approaches
     */
    ExplorationResult explore(String userInput, ExploreBudget budget);
}
