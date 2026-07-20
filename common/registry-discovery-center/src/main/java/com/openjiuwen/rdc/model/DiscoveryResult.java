/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.util.List;
import java.util.Objects;

/**
 * Structured discovery response (Feat-015 0711 scope §3 {@code DiscoveryResult}).
 *
 * @since 0.1.0 (2026)
 */
public record DiscoveryResult(
        DiscoveryOutcome outcome,
        List<DiscoveryCandidate> candidates,
        @Nullable String nextToken,
        String traceId
) {
    public DiscoveryResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(traceId, "traceId");
        candidates = List.copyOf(candidates);
    }

    /**
     * of.
     *
     * @param outcome outcome
     * @param candidates candidates
     * @param traceId traceId
     * @return result
     * @since 0.1.0
     */
    public static DiscoveryResult of(DiscoveryOutcome outcome, List<DiscoveryCandidate> candidates,
                                     String traceId) {
        return new DiscoveryResult(outcome, candidates, null, traceId);
    }
}
