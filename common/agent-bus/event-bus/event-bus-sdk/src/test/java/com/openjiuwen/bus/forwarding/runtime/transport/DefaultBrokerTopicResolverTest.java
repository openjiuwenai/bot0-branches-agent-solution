/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultBrokerTopicResolver} (FEAT-013/014 Option B) — pins the
 * event-type → route-family → topic convention for every {@link AgentBusEventType} constant
 * and the hop-suffix wiring.
 *
 * <p>Authority: {@code common/agent-bus/registry-discovery-center-integration-plan.md} §5.3 / §6.1.
 */
class DefaultBrokerTopicResolverTest {
    private final DefaultBrokerTopicResolver resolver = new DefaultBrokerTopicResolver();

    @Test
    void invocation_family_event_types_map_to_invocation_topic() {
        for (AgentBusEventType t : invocationFamilyTypes()) {
            assertThat(resolver.resolveTopic(t, "req"))
                    .as("CLIENT_*/INVOCATION_* → ascend_bus_invocation_<suffix>")
                    .isEqualTo("ascend_bus_invocation_req");
        }
    }

    @Test
    void a2a_family_event_types_map_to_a2a_topic() {
        for (AgentBusEventType t : a2aFamilyTypes()) {
            assertThat(resolver.resolveTopic(t, "deliver"))
                    .as("A2A_* → ascend_bus_a2a_<suffix>")
                    .isEqualTo("ascend_bus_a2a_deliver");
        }
    }

    @Test
    void every_event_type_is_covered_by_a_family() {
        // guards against a newly-added AgentBusEventType silently falling through to the invocation default
        int invocation = invocationFamilyTypes().length;
        int a2a = a2aFamilyTypes().length;
        assertThat(invocation + a2a)
                .as("every AgentBusEventType belongs to exactly one family")
                .isEqualTo(AgentBusEventType.values().length);
    }

    @Test
    void suffix_selects_the_hop_role() {
        assertThat(resolver.resolveTopic(AgentBusEventType.CLIENT_INVOCATION_REQUESTED, "req"))
                .isEqualTo("ascend_bus_invocation_req");
        assertThat(resolver.resolveTopic(AgentBusEventType.CLIENT_INVOCATION_REQUESTED, "deliver"))
                .isEqualTo("ascend_bus_invocation_deliver");
        assertThat(resolver.resolveTopic(AgentBusEventType.INVOCATION_RESPONSE, "resp_in"))
                .isEqualTo("ascend_bus_invocation_resp_in");
        assertThat(resolver.resolveTopic(AgentBusEventType.INVOCATION_RESPONSE, "resp_out"))
                .isEqualTo("ascend_bus_invocation_resp_out");
        assertThat(resolver.resolveTopic(AgentBusEventType.A2A_CALL_REQUESTED, "req"))
                .isEqualTo("ascend_bus_a2a_req");
        assertThat(resolver.resolveTopic(AgentBusEventType.A2A_CALL_RESPONSE, "resp_out"))
                .isEqualTo("ascend_bus_a2a_resp_out");
    }

    @Test
    void topic_prefix_constant_is_ascend_bus_() {
        assertThat(BrokerTopicResolver.TOPIC_PREFIX).isEqualTo("ascend_bus_");
    }

    @Test
    void route_family_helper_maps_a2a_vs_invocation() {
        assertThat(DefaultBrokerTopicResolver.routeFamily(AgentBusEventType.CLIENT_INVOCATION_REQUESTED))
                .isEqualTo(DefaultBrokerTopicResolver.FAMILY_INVOCATION);
        assertThat(DefaultBrokerTopicResolver.routeFamily(AgentBusEventType.INVOCATION_TERMINAL))
                .isEqualTo(DefaultBrokerTopicResolver.FAMILY_INVOCATION);
        assertThat(DefaultBrokerTopicResolver.routeFamily(AgentBusEventType.A2A_CALL_REQUESTED))
                .isEqualTo(DefaultBrokerTopicResolver.FAMILY_A2A);
        assertThat(DefaultBrokerTopicResolver.routeFamily(AgentBusEventType.A2A_STREAM_READY))
                .isEqualTo(DefaultBrokerTopicResolver.FAMILY_A2A);
    }

    @Test
    void rejects_null_event_type_and_blank_suffix() {
        assertThatThrownBy(() -> resolver.resolveTopic(null, "req"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> resolver.resolveTopic(AgentBusEventType.CLIENT_INVOCATION_REQUESTED, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("suffix");
        assertThatThrownBy(() -> resolver.resolveTopic(AgentBusEventType.CLIENT_INVOCATION_REQUESTED, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static AgentBusEventType[] invocationFamilyTypes() {
        return new AgentBusEventType[] {
                AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                AgentBusEventType.CLIENT_INVOCATION_CANCEL_REQUESTED,
                AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED,
                AgentBusEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED,
                AgentBusEventType.INVOCATION_ACCEPTED,
                AgentBusEventType.INVOCATION_REJECTED,
                AgentBusEventType.INVOCATION_FAILED,
                AgentBusEventType.INVOCATION_RESPONSE,
                AgentBusEventType.INVOCATION_INPUT_REQUIRED,
                AgentBusEventType.INVOCATION_STREAM_READY,
                AgentBusEventType.INVOCATION_TERMINAL
        };
    }

    private static AgentBusEventType[] a2aFamilyTypes() {
        return new AgentBusEventType[] {
                AgentBusEventType.A2A_CALL_REQUESTED,
                AgentBusEventType.A2A_CALL_CANCEL_REQUESTED,
                AgentBusEventType.A2A_CALL_QUERY_REQUESTED,
                AgentBusEventType.A2A_STREAM_SUBSCRIBE_REQUESTED,
                AgentBusEventType.A2A_CALL_ACCEPTED,
                AgentBusEventType.A2A_CALL_REJECTED,
                AgentBusEventType.A2A_CALL_FAILED,
                AgentBusEventType.A2A_CALL_RESPONSE,
                AgentBusEventType.A2A_CALL_INPUT_REQUIRED,
                AgentBusEventType.A2A_STREAM_READY,
                AgentBusEventType.A2A_CALL_TERMINAL
        };
    }
}
