/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

import java.util.List;

/**
 * Structured result of the Explore phase.
 *
 * @param findings structured summary of what was discovered
 * @param candidateApproaches list of candidate analysis angles or strategies
 *
 * @since 2026-07
 */
public record ExplorationResult(String findings, List<String> candidateApproaches) {
    /**
     * Compact constructor — defensive copies.
     *
     * @param findings structured summary of what was discovered
     * @param candidateApproaches list of candidate analysis angles or strategies
     */
    public ExplorationResult {
        candidateApproaches = candidateApproaches == null ? List.of() : List.copyOf(candidateApproaches);
    }

    /**
     * Convenience constructor for a single finding with no candidate approaches.
     *
     * @param findings structured summary of what was discovered
     */
    public ExplorationResult(String findings) {
        this(findings, List.of());
    }

    /**
     * Empty sentinel — used when exploration produced no actionable findings
     * (e.g. Explorer raised a defensive failure). Replaces null returns so
     * callers never need to null-check (G.MET.06 compliance).
     *
     * @return an ExplorationResult carrying no findings and no candidates
     */
    public static ExplorationResult empty() {
        return new ExplorationResult(null, List.of());
    }
}
