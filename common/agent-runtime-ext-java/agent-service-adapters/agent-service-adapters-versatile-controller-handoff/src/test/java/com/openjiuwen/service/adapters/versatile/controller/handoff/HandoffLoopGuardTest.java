/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.Test;

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
    void outboundAppendsSelfAndIncrementsHop() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        HandoffLoopGuard.OutboundDecision d = guard.prepareOutbound("agent_card_hotel", "k1",
                request(Map.of("handoffHopCount", 1, "handoffRouteTrace", List.of("agent_card_l1"),
                        "sourceAgentId", "agent_card_l1")),
                new RequestHandoffState());
        assertThat(d.result()).isEqualTo(HandoffLoopGuard.GuardResult.ALLOW);
        assertThat(d.metadata()).containsEntry("handoffHopCount", 2);
        // 入站轨迹已含 self：不重复追加（轨迹记录访问过的 agent，重复无意义）
        assertThat((List<Object>) d.metadata().get("handoffRouteTrace"))
                .containsExactly("agent_card_l1");
        assertThat(d.metadata()).containsEntry("sourceAgentId", "agent_card_l1");
    }

    @Test
    void outboundFirstHopInitializesSourceAndTrace() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        HandoffLoopGuard.OutboundDecision d = guard.prepareOutbound("agent_card_hotel", "k1",
                request(null), new RequestHandoffState());
        assertThat(d.metadata()).containsEntry("handoffHopCount", 1);
        assertThat((List<Object>) d.metadata().get("handoffRouteTrace")).containsExactly("agent_card_l1");
        assertThat(d.metadata()).containsEntry("sourceAgentId", "agent_card_l1");
    }

    @Test
    void duplicateDedupKeySkipsSecondCall() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        ServeRequest req = request(null);
        RequestHandoffState state = new RequestHandoffState();
        guard.prepareOutbound("a", "k1", req, state);
        assertThat(guard.prepareOutbound("a", "k1", req, state).result())
                .isEqualTo(HandoffLoopGuard.GuardResult.DUPLICATE_MESSAGE);
    }

    @Test
    void redirectCountOverLimitYieldsLoopLimit() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        ServeRequest req = request(null);
        RequestHandoffState state = new RequestHandoffState();
        guard.prepareOutbound("a", "k1", req, state);
        guard.prepareOutbound("b", "k2", req, state);
        assertThat(guard.prepareOutbound("c", "k3", req, state).result())
                .isEqualTo(HandoffLoopGuard.GuardResult.LOOP_LIMIT);
    }

    @Test
    void repeatedTargetInSameRequestYieldsDuplicateTarget() {
        HandoffLoopGuard guard = new HandoffLoopGuard(properties());
        ServeRequest req = request(null);
        RequestHandoffState state = new RequestHandoffState();
        guard.prepareOutbound("a", "k1", req, state);
        HandoffLoopGuard.OutboundDecision d = guard.prepareOutbound("a", "k2", req, state);
        assertThat(d.result()).isEqualTo(HandoffLoopGuard.GuardResult.DUPLICATE_TARGET);
    }
}
