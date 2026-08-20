/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HandoffLoopGuardTest {

    private ControllerHandoffProperties properties() {
        ControllerHandoffProperties p = new ControllerHandoffProperties();
        p.setSelfAgentId("agent_card_l1");
        p.getLoop().setMaxRedirects(2);
        return p;
    }

    private ServeRequest request(Map<String, Object> metadata) {
        ServeRequest r = new ServeRequest();
        r.setConversationId("c1");
        r.setMetadata(metadata == null ? Map.of() : metadata);
        return r;
    }

    @Test
    void inboundAllowsWhenTraceMetadataAbsentButLogsDegradation() {
        assertThat(new HandoffLoopGuard(properties()).checkInbound(request(null)))
                .isEqualTo(HandoffLoopGuard.GuardResult.ALLOW);
    }

    @Test
    void inboundRejectsWhenHopCountExceedsLimit() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        assertThat(guard.checkInbound(request(Map.of("handoffHopCount", 9))))
                .isEqualTo(HandoffLoopGuard.GuardResult.LOOP_LIMIT);
    }

    @Test
    void inboundDetectsReturnToSelfAgent() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        assertThat(guard.checkInbound(request(Map.of(
                "handoffHopCount", 2,
                "handoffRouteTrace", List.of("agent_card_l1", "agent_card_hotel")))))
                .isEqualTo(HandoffLoopGuard.GuardResult.DUPLICATE_TARGET);
    }

    @Test
    void outboundTraceMetadataIncrementsHopAndAppendsSelf() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("handoffHopCount", 2);
        metadata.put("handoffRouteTrace", List.of("agent_x", "agent_y"));
        metadata.put("sourceAgentId", "agent_x");
        Map<String, Object> outbound = guard.outboundTraceMetadata(request(metadata));
        assertThat(outbound.get("handoffHopCount")).isEqualTo(3);
        assertThat((List<Object>) outbound.get("handoffRouteTrace"))
                .containsExactly("agent_x", "agent_y", "agent_card_l1");
        assertThat(outbound.get("sourceAgentId")).isEqualTo("agent_x");
    }

    @Test
    void outboundTraceMetadataInitializesWhenAbsent() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        Map<String, Object> outbound = guard.outboundTraceMetadata(request(null));
        assertThat(outbound.get("handoffHopCount")).isEqualTo(1);
        assertThat((List<Object>) outbound.get("handoffRouteTrace")).containsExactly("agent_card_l1");
        assertThat(outbound.get("sourceAgentId")).isEqualTo("agent_card_l1");
    }
}
