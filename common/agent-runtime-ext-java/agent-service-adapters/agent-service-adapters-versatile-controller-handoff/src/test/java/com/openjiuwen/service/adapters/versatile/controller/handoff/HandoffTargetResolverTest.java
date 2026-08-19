/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandoffTargetResolverTest {

    private ControllerHandoffProperties properties() {
        ControllerHandoffProperties p = new ControllerHandoffProperties();
        ControllerHandoffProperties.Target t = p.getTarget();
        t.setAllowedAgents(List.of("agent_card_l1", "agent_card_hotel", "agent_card_flight"));
        t.setIntentMapping(Map.of("intent_hotel", "agent_card_hotel"));
        t.setDomainMapping(Map.of("flight", "agent_card_flight"));
        return p;
    }

    private IntentHandoff handoff(String targetAgentId, String intentId, String domain) {
        return new IntentHandoff("L1_TO_L2", intentId, domain, targetAgentId, null, "{}");
    }

    @Test
    void directTargetHasHighestPriority() {
        ResolvedTarget t = new HandoffTargetResolver(properties())
                .resolve(handoff("agent_card_flight", "intent_hotel", "hotel"));
        assertThat(t.agentId()).isEqualTo("agent_card_flight");
        assertThat(t.source()).isEqualTo(ResolvedTarget.ResolutionSource.DIRECT);
    }

    @Test
    void directTargetMustPassAllowlist() {
        assertThatThrownBy(() -> new HandoffTargetResolver(properties())
                .resolve(handoff("agent_card_rogue", null, null)))
                .isInstanceOf(HandoffTargetResolutionException.class)
                .satisfies(ex -> assertThat(((HandoffTargetResolutionException) ex).getErrorCode())
                        .isEqualTo("VERSATILE_HANDOFF_TARGET_NOT_ALLOWED"));
    }

    @Test
    void intentMappingUsedWhenNoDirectTarget() {
        ResolvedTarget t = new HandoffTargetResolver(properties())
                .resolve(handoff(null, "intent_hotel", null));
        assertThat(t.agentId()).isEqualTo("agent_card_hotel");
        assertThat(t.source()).isEqualTo(ResolvedTarget.ResolutionSource.INTENT_MAPPING);
    }

    @Test
    void domainMappingUsedWhenIntentUnmapped() {
        ResolvedTarget t = new HandoffTargetResolver(properties())
                .resolve(handoff(null, "intent_unknown", "flight"));
        assertThat(t.agentId()).isEqualTo("agent_card_flight");
        assertThat(t.source()).isEqualTo(ResolvedTarget.ResolutionSource.DOMAIN_MAPPING);
    }

    @Test
    void mappedTargetOutsideAllowlistRejected() {
        ControllerHandoffProperties p = properties();
        p.getTarget().setIntentMapping(Map.of("intent_hotel", "agent_card_rogue"));
        assertThatThrownBy(() -> new HandoffTargetResolver(p).resolve(handoff(null, "intent_hotel", null)))
                .isInstanceOf(HandoffTargetResolutionException.class)
                .satisfies(ex -> assertThat(((HandoffTargetResolutionException) ex).getErrorCode())
                        .isEqualTo("VERSATILE_HANDOFF_TARGET_NOT_ALLOWED"));
    }

    @Test
    void noResolvableTargetYieldsMissing() {
        assertThatThrownBy(() -> new HandoffTargetResolver(properties()).resolve(handoff(null, null, null)))
                .isInstanceOf(HandoffTargetResolutionException.class)
                .satisfies(ex -> assertThat(((HandoffTargetResolutionException) ex).getErrorCode())
                        .isEqualTo("VERSATILE_HANDOFF_TARGET_MISSING"));
    }

    @Test
    void priorityOrderIsConfigurable() {
        ControllerHandoffProperties p = properties();
        p.getTarget().setResolutionPriority(List.of("domain", "direct"));
        ResolvedTarget t = new HandoffTargetResolver(p)
                .resolve(handoff("agent_card_hotel", null, "flight"));
        assertThat(t.agentId()).isEqualTo("agent_card_flight");
        assertThat(t.source()).isEqualTo(ResolvedTarget.ResolutionSource.DOMAIN_MAPPING);
    }

    @Test
    void blankDirectTargetTreatedAsAbsent() {
        ResolvedTarget t = new HandoffTargetResolver(properties())
                .resolve(handoff("  ", "intent_hotel", null));
        assertThat(t.source()).isEqualTo(ResolvedTarget.ResolutionSource.INTENT_MAPPING);
    }
}
