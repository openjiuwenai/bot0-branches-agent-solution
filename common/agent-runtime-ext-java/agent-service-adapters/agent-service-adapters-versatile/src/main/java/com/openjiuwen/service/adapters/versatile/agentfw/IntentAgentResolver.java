/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties.MappingCandidate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Resolves the final {@code agent_id} (A2A Gateway {@code agentCard} path segment)
 * from a workflow-returned value or the configured 1:N {@code intent-agent-mapping}
 * using a deployment-time selection strategy.
 *
 * <p>Strategies are static deployment rules — they do not read session content or
 * user profile; round-robin cursors are per-{@code intent_id} and in-memory only
 * (reset on process restart).
 */
final class IntentAgentResolver {
    private final VersatileProperties properties;
    private final Map<String, AtomicInteger> roundRobinCursors = new ConcurrentHashMap<>();

    IntentAgentResolver(VersatileProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    Optional<String> resolve(String intentId, String workflowAgentId) {
        if (workflowAgentId != null && !workflowAgentId.isBlank()) {
            return Optional.of(workflowAgentId);
        }
        if (intentId == null || intentId.isBlank()) {
            throw new IllegalStateException(
                    "VERSATILE_INTENT_AGENT_ID_UNMAPPED: intent_id is blank and workflow did not return agent_id");
        }
        List<MappingCandidate> candidates = properties.getIntentAgentMapping().get(intentId);
        if (candidates == null || candidates.isEmpty()) {
            throw new IllegalStateException(
                    "VERSATILE_INTENT_AGENT_ID_UNMAPPED: no candidate agentCard for intent_id=" + intentId);
        }
        return Optional.of(selectByStrategy(intentId, candidates));
    }

    private String selectByStrategy(String intentId, List<MappingCandidate> candidates) {
        VersatileProperties.IntentAgentMappingStrategy strategy = properties.getIntentAgentMappingStrategy();
        if (strategy == VersatileProperties.IntentAgentMappingStrategy.PRIORITY) {
            return candidates.stream()
                    .filter(c -> c.getAgentCard() != null && !c.getAgentCard().isBlank())
                    .min(java.util.Comparator.comparingInt(MappingCandidate::getPriority))
                    .map(MappingCandidate::getAgentCard)
                    .orElseThrow(() -> new IllegalStateException(
                            "VERSATILE_INTENT_AGENT_ID_UNMAPPED: no valid agentCard for intent_id=" + intentId));
        }
        if (strategy == VersatileProperties.IntentAgentMappingStrategy.ROUND_ROBIN) {
            List<MappingCandidate> valid = candidates.stream()
                    .filter(c -> c.getAgentCard() != null && !c.getAgentCard().isBlank())
                    .toList();
            if (valid.isEmpty()) {
                throw new IllegalStateException(
                        "VERSATILE_INTENT_AGENT_ID_UNMAPPED: no valid agentCard for intent_id=" + intentId);
            }
            AtomicInteger cursor = roundRobinCursors.computeIfAbsent(intentId, k -> new AtomicInteger(0));
            int index = Math.floorMod(cursor.getAndIncrement(), valid.size());
            return valid.get(index).getAgentCard();
        }
        // FIRST (default)
        return candidates.stream()
                .filter(c -> c.getAgentCard() != null && !c.getAgentCard().isBlank())
                .findFirst()
                .map(MappingCandidate::getAgentCard)
                .orElseThrow(() -> new IllegalStateException(
                        "VERSATILE_INTENT_AGENT_ID_UNMAPPED: no valid agentCard for intent_id=" + intentId));
    }
}
