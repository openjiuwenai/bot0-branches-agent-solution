/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.gateway.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;

import org.junit.jupiter.api.Test;

/**
 * Pins the P-06 / FEAT-014:68 response-side contract: the A2A response envelope (taskId / status /
 * streamRef / reason) rides {@code inlinePayload} (DATA), NOT a control-descriptor {@code payloadRef}.
 * The gateway — the A2A-aware caller — interprets the inline response content; the bus carries it
 * opaquely. Pre-P-06 the gateway peeked these tokens out of {@code payloadRef}; that conflated the
 * data reference with response content.
 */
class GatewayRuntimeServiceResponseTest {
    @Test
    void responseToken_reads_response_content_from_inline_payload() {
        BrokerInboundMessage accepted = response(AgentBusEventType.INVOCATION_ACCEPTED, "taskId=task-1", null);
        assertThat(GatewayRuntimeService.responseToken(accepted, "taskId")).isEqualTo("task-1");

        BrokerInboundMessage terminal = response(AgentBusEventType.INVOCATION_TERMINAL, "status=completed", null);
        assertThat(GatewayRuntimeService.responseToken(terminal, "status")).isEqualTo("completed");

        BrokerInboundMessage stream = response(AgentBusEventType.INVOCATION_STREAM_READY,
                "streamRef=stream://t1", null);
        assertThat(GatewayRuntimeService.responseToken(stream, "streamRef")).isEqualTo("stream://t1");

        BrokerInboundMessage rejected = response(AgentBusEventType.INVOCATION_REJECTED, "reason=denied", null);
        assertThat(GatewayRuntimeService.responseToken(rejected, "reason")).isEqualTo("denied");
    }

    @Test
    void responseToken_does_not_read_a_descriptor_left_in_payload_ref() {
        // payloadRef is the A2A data reference, NOT the response-content channel. A leftover control-
        // descriptor token there must NOT be read (pre-P-06 it was — that conflated control + data).
        BrokerInboundMessage legacy = response(AgentBusEventType.INVOCATION_ACCEPTED, null, "taskId=task-1");
        assertThat(GatewayRuntimeService.responseToken(legacy, "taskId")).isNull();
    }

    @Test
    void responseToken_tolerates_absent_or_malformed_inline_payload() {
        // the record ctor rejects a BLANK inlinePayload (wiring error), so the gateway only ever sees
        // null or non-blank; a non-blank body without the sought key (or with no '=' at all) → null.
        assertThat(GatewayRuntimeService.responseToken(
                response(AgentBusEventType.INVOCATION_ACCEPTED, null, null), "taskId")).isNull();
        assertThat(GatewayRuntimeService.responseToken(
                response(AgentBusEventType.INVOCATION_ACCEPTED, "malformed-no-eq", null), "taskId")).isNull();
        assertThat(GatewayRuntimeService.responseToken(
                response(AgentBusEventType.INVOCATION_ACCEPTED, "otherKey=other", null), "taskId")).isNull();
    }

    @Test
    void classify_maps_input_required_events_to_input_required_status() {
        // FEAT-017: *_INPUT_REQUIRED projects a wait-for-input state. classify must map it to
        // INPUT_REQUIRED (not the default UNKNOWN) so the gateway can surface the wait-for-input
        // projection promptly instead of treating it as an unknown / control-only echo.
        assertThat(GatewayRuntimeService.classify(
                response(AgentBusEventType.INVOCATION_INPUT_REQUIRED, "taskId=task-1", null)))
                .isEqualTo(InvocationResponseStatus.INPUT_REQUIRED);
        assertThat(GatewayRuntimeService.classify(
                response(AgentBusEventType.A2A_CALL_INPUT_REQUIRED, "taskId=task-2", null)))
                .isEqualTo(InvocationResponseStatus.INPUT_REQUIRED);
    }

    private static BrokerInboundMessage response(AgentBusEventType eventType, String inlinePayload,
                                                  String payloadRef) {
        return new BrokerInboundMessage("t1", "m1", "runtime-1", "gateway-1", "gateway-1",
                payloadRef, "c1", eventType,
                "trace-1", "idem-1", "route-1", "a2a", Long.MAX_VALUE, inlinePayload, null);
    }
}
