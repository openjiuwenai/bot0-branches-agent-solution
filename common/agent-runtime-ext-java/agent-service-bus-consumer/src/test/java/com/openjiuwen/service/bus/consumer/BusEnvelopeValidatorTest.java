/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.validation.BusEnvelopeValidator;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * Tests BusEnvelopeValidator behavior.
 *
 * @since 2026-07-22
 */
class BusEnvelopeValidatorTest {
    private final Instant now = Instant.parse("2026-07-20T00:00:00Z");
    private final BusEnvelopeValidator validator = new BusEnvelopeValidator(Clock.fixed(now, ZoneOffset.UTC),
            "tenant-a", "runtime-a");

    @Test
    void acceptsValidInlineRequest() {
        assertThat(validator
                .validate(event("CLIENT_INVOCATION_REQUESTED", "runtime-a", now.plusSeconds(10), new byte[]{1}, null)))
                .isEmpty();
    }

    @Test
    void rejectsExpiredAndWrongTarget() {
        assertThat(validator
                .validate(event("CLIENT_INVOCATION_REQUESTED", "runtime-b", now.minusSeconds(1), new byte[]{1}, null)))
                .contains("TARGET_MISMATCH");
        assertThat(validator
                .validate(event("CLIENT_INVOCATION_REQUESTED", "runtime-a", now.minusSeconds(1), new byte[]{1}, null)))
                .contains("DEADLINE_EXCEEDED");
    }

    @Test
    void rejectsEnvelopeOutsideConfiguredAgentBusTenant() {
        AgentBusEventEnvelope event = event("CLIENT_INVOCATION_REQUESTED", "runtime-a", now.plusSeconds(10),
                new byte[]{1}, null);
        assertThat(validator.validate(withTenant(event, "tenant-b")))
                .contains("TENANT_SCOPE_VIOLATION");
    }

    @Test
    void rejectsAmbiguousPayload() {
        assertThat(validator.validate(
                event("CLIENT_INVOCATION_REQUESTED", "runtime-a", now.plusSeconds(1), new byte[]{1}, "ref://payload")))
                .contains("PAYLOAD_REFERENCE_INVALID");
    }

    @Test
    void acceptsMetadataWithoutAnIndependentSizeLimit() {
        AgentBusEventEnvelope event = event("CLIENT_INVOCATION_REQUESTED", "runtime-a", now.plusSeconds(10),
                new byte[]{1}, null);
        assertThat(validator.validate(withMetadata(event, Map.of("context", "x".repeat(16_385)))))
                .isEmpty();
    }

    @Test
    void parsesClientAndA2aEventFamilies() {
        assertThat(event("A2A_CALL_REQUESTED", "runtime-a", now.plusSeconds(1), new byte[]{1}, null).parsedEventType())
                .isEqualTo(AgentBusEventType.A2A_CALL_REQUESTED);
        assertThat(event("CLIENT_INVOCATION_REQUESTED", "runtime-a", now.plusSeconds(1), new byte[]{1}, null)
                .parsedEventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
    }

    private AgentBusEventEnvelope event(String type, String target, Instant deadline, byte[] inline, String ref) {
        return new AgentBusEventEnvelope(type, "m-1", "tenant-a", "source", target, null, "corr-1", "trace-1",
                "idem-1", deadline, "application/json", inline, ref, Map.of());
    }

    private AgentBusEventEnvelope withTenant(AgentBusEventEnvelope event, String tenantId) {
        return new AgentBusEventEnvelope(event.eventType(), event.messageId(), tenantId, event.sourceServiceId(),
                event.targetServiceId(), event.routeHandle(), event.correlationId(), event.traceId(),
                event.idempotencyKey(), event.deadline(), event.payloadContentType(), event.inlinePayload(),
                event.payloadRef(), event.metadata());
    }

    private AgentBusEventEnvelope withMetadata(AgentBusEventEnvelope event, Map<String, String> metadata) {
        return new AgentBusEventEnvelope(event.eventType(), event.messageId(), event.tenantId(),
                event.sourceServiceId(), event.targetServiceId(), event.routeHandle(), event.correlationId(),
                event.traceId(), event.idempotencyKey(), event.deadline(), event.payloadContentType(),
                event.inlinePayload(), event.payloadRef(), metadata);
    }
}
