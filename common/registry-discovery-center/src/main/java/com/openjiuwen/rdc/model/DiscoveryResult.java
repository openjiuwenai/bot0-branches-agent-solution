package com.openjiuwen.rdc.model;

import java.util.List;
import java.util.Objects;

/**
 * Structured discovery response (Feat-015 0711 scope §3 {@code DiscoveryResult}).
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

    public static DiscoveryResult of(DiscoveryOutcome outcome, List<DiscoveryCandidate> candidates,
                                     String traceId) {
        return new DiscoveryResult(outcome, candidates, null, traceId);
    }
}
