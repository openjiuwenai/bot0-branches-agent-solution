/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;

import java.util.Map;
import java.util.Optional;

/**
 * Resolves the downstream agent by priority (default: direct &gt; intent &gt; domain &gt; fixed-l1)
 * and validates the allowlist. Mappings are deployment configuration, 1:1 unique by
 * the Map binding — the adapter never performs multi-target load selection (spec 4.4/5.5).
 *
 * @since 2026-08-19
 */
public class HandoffTargetResolver {
    private final ControllerHandoffProperties properties;

    public HandoffTargetResolver(ControllerHandoffProperties properties) {
        this.properties = properties;
    }

    public ResolvedTarget resolve(IntentHandoff handoff) {
        ControllerHandoffProperties.Target target = properties.getTarget();
        for (String source : target.getResolutionPriority()) {
            Optional<String> candidate = switch (source) {
                case "direct" -> direct(handoff);
                case "intent" -> fromMapping(target.getIntentMapping(), handoff.intentId());
                case "domain" -> fromMapping(target.getDomainMapping(), handoff.businessDomain());
                case "fixed-l1" -> fixedL1(target);
                default -> Optional.empty();
            };
            if (candidate.isPresent()) {
                String agentId = candidate.get();
                if (!target.getAllowedAgents().contains(agentId)) {
                    throw new HandoffTargetResolutionException("VERSATILE_HANDOFF_TARGET_NOT_ALLOWED",
                            "target not in allowed-agents: " + agentId);
                }
                return new ResolvedTarget(agentId, sourceOf(source));
            }
        }
        throw new HandoffTargetResolutionException("VERSATILE_HANDOFF_TARGET_MISSING",
                "no unique target resolved for intent=" + handoff.intentId()
                        + " domain=" + handoff.businessDomain()
                        + " directTarget=" + handoff.targetAgentId());
    }

    private static Optional<String> direct(IntentHandoff handoff) {
        return Optional.ofNullable(handoff.targetAgentId())
                .filter(id -> !id.isBlank());
    }

    private static Optional<String> fromMapping(Map<String, String> mapping, String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapping.get(key));
    }

    private static Optional<String> fixedL1(ControllerHandoffProperties.Target target) {
        return Optional.ofNullable(target.getFixedL1Entry()).filter(id -> !id.isBlank());
    }

    private static ResolvedTarget.ResolutionSource sourceOf(String source) {
        return switch (source) {
            case "direct" -> ResolvedTarget.ResolutionSource.DIRECT;
            case "intent" -> ResolvedTarget.ResolutionSource.INTENT_MAPPING;
            case "domain" -> ResolvedTarget.ResolutionSource.DOMAIN_MAPPING;
            case "fixed-l1" -> ResolvedTarget.ResolutionSource.FIXED_L1;
            default -> throw new IllegalArgumentException("unknown resolution source: " + source);
        };
    }
}
