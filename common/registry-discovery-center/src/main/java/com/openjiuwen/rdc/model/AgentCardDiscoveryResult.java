package com.openjiuwen.rdc.model;

import java.util.List;

/**
 * Structured Agent Card discovery response (Feat-015 0713
 * {@code AgentCardDiscoveryResult}).
 */
public record AgentCardDiscoveryResult(
        DiscoveryOutcome outcome,
        List<AgentCardCandidate> candidates,
        @Nullable String nextToken,
        String traceId
) {
    public AgentCardDiscoveryResult {
        candidates = List.copyOf(candidates);
    }

    public static AgentCardDiscoveryResult of(DiscoveryOutcome outcome,
                                              List<AgentCardCandidate> candidates,
                                              String traceId) {
        return new AgentCardDiscoveryResult(outcome, candidates, null, traceId);
    }

    public static AgentCardDiscoveryResult from(DiscoveryResult result) {
        List<AgentCardCandidate> mapped = result.candidates().stream()
                .map(AgentCardCandidate::from)
                .toList();
        return new AgentCardDiscoveryResult(
                result.outcome(), mapped, result.nextToken(), result.traceId());
    }

    public DiscoveryResult toDiscoveryResult() {
        List<DiscoveryCandidate> mapped = candidates.stream()
                .map(c -> DiscoveryCandidate.builder()
                        .agentCardJson(c.agentCardJson())
                        .agentId(c.agentId())
                        .serviceId(c.serviceId())
                        .matchedA2aSkillId(c.matchedA2aSkillId())
                        .contractVersion(c.contractVersion())
                        .capabilityVersion(c.capabilityVersion())
                        .registrationStatus(c.registrationStatus())
                        .freshness(c.freshness())
                        .lastValidatedAt(c.lastValidatedAt())
                        .build())
                .toList();
        return new DiscoveryResult(outcome, mapped, nextToken, traceId);
    }
}
