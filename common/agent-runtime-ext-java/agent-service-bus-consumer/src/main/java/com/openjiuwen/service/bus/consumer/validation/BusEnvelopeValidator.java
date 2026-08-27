/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.validation;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Validates the trust boundary and resource limits of inbound bus envelopes.
 *
 * @since 2026-07-22
 */
public final class BusEnvelopeValidator {
    private static final Set<String> EVENTS = Set.of("CLIENT_INVOCATION_REQUESTED", "CLIENT_INVOCATION_QUERY_REQUESTED",
            "CLIENT_STREAM_SUBSCRIBE_REQUESTED", "A2A_CALL_REQUESTED", "A2A_CALL_QUERY_REQUESTED",
            "A2A_STREAM_SUBSCRIBE_REQUESTED");
    private static final int MAX_FIELD_CHARS = 1_024;

    private final Clock clock;
    private final String tenantId;
    private final String targetServiceId;
    private final int maxInlinePayloadBytes;
    private final long maxDeadlineAheadSeconds;

    /**
     * Creates a new instance.
     *
     * @param clock
     *            the clock value
     * @param tenantId
     *            the configured agent-bus tenant scope
     * @param targetServiceId
     *            the targetServiceId value
     */
    public BusEnvelopeValidator(Clock clock, String tenantId, String targetServiceId) {
        this(clock, tenantId, targetServiceId, 65_536, 86_400);
    }

    /**
     * Creates a new instance.
     *
     * @param clock
     *            the clock value
     * @param tenantId
     *            the configured agent-bus tenant scope
     * @param targetServiceId
     *            the targetServiceId value
     * @param maxInlinePayloadBytes
     *            the maxInlinePayloadBytes value
     * @param maxDeadlineAheadSeconds
     *            the maxDeadlineAheadSeconds value
     */
    public BusEnvelopeValidator(Clock clock, String tenantId, String targetServiceId, int maxInlinePayloadBytes,
            long maxDeadlineAheadSeconds) {
        if (maxInlinePayloadBytes < 1 || maxDeadlineAheadSeconds < 1) {
            throw new IllegalArgumentException("validator limits must be positive");
        }
        this.clock = clock;
        this.tenantId = tenantId;
        this.targetServiceId = targetServiceId;
        this.maxInlinePayloadBytes = maxInlinePayloadBytes;
        this.maxDeadlineAheadSeconds = maxDeadlineAheadSeconds;
    }

    /**
     * Performs the validate operation.
     *
     * @param envelope
     *            the envelope value
     *
     * @return the operation result
     */
    public Optional<String> validate(AgentBusEventEnvelope envelope) {
        if (envelope == null) {
            return Optional.of("ENVELOPE_NULL");
        }
        if (!EVENTS.contains(envelope.eventType())) {
            return Optional.of("UNSUPPORTED_EVENT_TYPE");
        }
        if (hasMissingRequiredField(envelope) || hasOversizedField(envelope)) {
            return Optional.of("INVALID_ENVELOPE");
        }
        if (blank(tenantId) || !tenantId.equals(envelope.tenantId())) {
            return Optional.of("TENANT_SCOPE_VIOLATION");
        }
        if (blank(targetServiceId) || !targetServiceId.equals(envelope.targetServiceId())) {
            return Optional.of("TARGET_MISMATCH");
        }
        Instant now = Instant.now(clock);
        if (envelope.deadline() == null || !envelope.deadline().isAfter(now)) {
            return Optional.of("DEADLINE_EXCEEDED");
        }
        if (envelope.deadline().isAfter(now.plusSeconds(maxDeadlineAheadSeconds))) {
            return Optional.of("INVALID_ENVELOPE");
        }
        if (envelope.inlinePayload() == null && blank(envelope.payloadRef())) {
            return Optional.of("PAYLOAD_REFERENCE_INVALID");
        }
        if (envelope.inlinePayload() != null && !blank(envelope.payloadRef())) {
            return Optional.of("PAYLOAD_REFERENCE_INVALID");
        }
        if (envelope.inlinePayload() != null && envelope.inlinePayload().length > maxInlinePayloadBytes) {
            return Optional.of("PAYLOAD_TOO_LARGE");
        }
        return Optional.empty();
    }

    private static boolean hasMissingRequiredField(AgentBusEventEnvelope envelope) {
        return requiredFields(envelope).anyMatch(BusEnvelopeValidator::blank);
    }

    private static boolean hasOversizedField(AgentBusEventEnvelope envelope) {
        return requiredFields(envelope).anyMatch(BusEnvelopeValidator::tooLong);
    }

    private static Stream<String> requiredFields(AgentBusEventEnvelope envelope) {
        return Stream.of(envelope.messageId(), envelope.tenantId(), envelope.sourceServiceId(),
                envelope.targetServiceId(), envelope.correlationId(), envelope.traceId(), envelope.idempotencyKey());
    }

    private static boolean tooLong(String value) {
        return value != null && value.length() > MAX_FIELD_CHARS;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
