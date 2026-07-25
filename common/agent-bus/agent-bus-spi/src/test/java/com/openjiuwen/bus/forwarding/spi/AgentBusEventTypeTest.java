/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

/**
 * Pins the FEAT-013 + FEAT-014 + FEAT-017 event-family membership of
 * {@link AgentBusEventType} (22 discriminators) so a later edit cannot silently
 * drop or rename a discriminator the gateway / event-bus / agent-runtime wire to.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md §2.3.2};
 * {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-014-a2a-call-event-forwarding.md §2.3.1};
 * {@code version-scope/FEAT-017-bus-event-subscription-consumption.md §5.1}.
 */
class AgentBusEventTypeTest {
    @Test
    void has_all_22_event_types_across_feat_013_014_017() {
        assertThat(EnumSet.allOf(AgentBusEventType.class)).containsExactlyInAnyOrder(
                // FEAT-013: client -> server (gateway -> event-bus -> agent-runtime)
                AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                AgentBusEventType.CLIENT_INVOCATION_CANCEL_REQUESTED,
                AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED,
                AgentBusEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED,
                // FEAT-013: server -> gateway (agent-runtime -> event-bus -> gateway)
                AgentBusEventType.INVOCATION_ACCEPTED,
                AgentBusEventType.INVOCATION_REJECTED,
                AgentBusEventType.INVOCATION_FAILED,
                AgentBusEventType.INVOCATION_RESPONSE,
                // FEAT-017: wait-for-input projection (server -> gateway)
                AgentBusEventType.INVOCATION_INPUT_REQUIRED,
                AgentBusEventType.INVOCATION_STREAM_READY,
                AgentBusEventType.INVOCATION_TERMINAL,
                // FEAT-014: source runtime -> target runtime (caller -> event-bus -> target)
                AgentBusEventType.A2A_CALL_REQUESTED,
                AgentBusEventType.A2A_CALL_CANCEL_REQUESTED,
                AgentBusEventType.A2A_CALL_QUERY_REQUESTED,
                AgentBusEventType.A2A_STREAM_SUBSCRIBE_REQUESTED,
                // FEAT-014: target runtime -> source runtime (target -> event-bus -> caller)
                AgentBusEventType.A2A_CALL_ACCEPTED,
                AgentBusEventType.A2A_CALL_REJECTED,
                AgentBusEventType.A2A_CALL_FAILED,
                AgentBusEventType.A2A_CALL_RESPONSE,
                // FEAT-017: wait-for-input projection (target -> caller)
                AgentBusEventType.A2A_CALL_INPUT_REQUIRED,
                AgentBusEventType.A2A_STREAM_READY,
                AgentBusEventType.A2A_CALL_TERMINAL);
    }

    @Test
    void value_of_rejects_an_unknown_event_type() {
        assertThatThrownBy(() -> AgentBusEventType.valueOf("NOT_A_REAL_EVENT_TYPE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void includes_feat_017_input_required_response_events() {
        // FEAT-017 mandates INPUT_REQUIRED as a bus response event projection (not only a
        // Task query result): when a Task enters INPUT_REQUIRED, the runtime MUST publish
        // INVOCATION_INPUT_REQUIRED / A2A_CALL_INPUT_REQUIRED so the gateway / caller runtime
        // can perceive the wait-for-input state promptly. The enum must therefore carry both
        // family variants — otherwise a runtime cannot stamp them on a ForwardingEnvelope and
        // AgentBusEventType.valueOf(...) would throw at publish time (FEAT-017 P-07).
        assertThat(AgentBusEventType.values())
                .extracting(Enum::name)
                .contains("INVOCATION_INPUT_REQUIRED", "A2A_CALL_INPUT_REQUIRED");
        assertThat(AgentBusEventType.values()).hasSize(22);
    }
}
