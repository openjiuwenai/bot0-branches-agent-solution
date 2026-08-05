/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

/**
 * Budget constraints for the Explore phase.
 *
 * @param maxRounds maximum exploration rounds (default 2)
 * @param maxSubAgents maximum subagents that can be spawned (default 3)
 * @param timeoutMillis per-round timeout in milliseconds (default 60000)
 *
 * @since 2026-07
 */
public record ExploreBudget(int maxRounds, int maxSubAgents, int timeoutMillis) {
    /** Default budget: 2 rounds, 3 subagents, 60s per round. */
    public static final ExploreBudget DEFAULT = new ExploreBudget(2, 3, 60_000);

    /**
     * Compact constructor — validates positive values.
     *
     * @param maxRounds maximum exploration rounds
     * @param maxSubAgents maximum subagents
     * @param timeoutMillis per-round timeout
     */
    public ExploreBudget {
        if (maxRounds < 0) {
            throw new IllegalArgumentException("maxRounds must be >= 0, got: " + maxRounds);
        }
        if (maxSubAgents < 0) {
            throw new IllegalArgumentException("maxSubAgents must be >= 0, got: " + maxSubAgents);
        }
        if (timeoutMillis < 0) {
            throw new IllegalArgumentException("timeoutMillis must be >= 0, got: " + timeoutMillis);
        }
    }
}
