/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import java.util.Objects;

/**
 * Broker-agnostic message headers carried alongside a {@link BrokerOutboundMessage}
 * body (Stage 26, T4 hybrid).
 *
 * <p>These headers are the broker-side vehicle for the routing metadata a receiver
 * needs to dedup / route / fetch the payload — they are NOT the payload body
 * (decision §6.2 ②: payload body / token stream / Task state never enter the
 * broker message). {@code payloadRef} rides as a header (conditionally required,
 * mirroring {@code ForwardingEnvelope.PayloadPolicy}: present for data-bearing
 * messages, absent for control-only).
 *
 * <p>Broker product concepts (topic / partition / offset / consumer-group) NEVER
 * appear here — those are adapter-internal (decision §6.2 ① spirit). {@code tenantId}
 * is mandatory and is the L2 header-tenant-check defense (a receiver polls with its
 * own tenant and rejects any message whose header tenantId differs — decision §6.2 ⑤).
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-transport-decision.md} (Stage 25 §10 guardrails,
 * Stage 26 broker SPI scaffold).
 */
// scope: forwarding transport.broker — routing metadata headers; never a payload body
public record BrokerMessageHeaders(
        String tenantId,
        String messageId,
        String sourceServiceId,
        String targetServiceId,
        String payloadRef,
        // FEAT-013 cross-hop correlation key (mirrored from the outbox record at produce). Nullable:
        // control-only messages or JDBC-back-compat records carry null; the gateway matches responses by it.
        String correlationId,
        // FEAT-013/014 event-type discriminator (mirrored from the record at produce). Nullable; the
        // gateway (S2) classifies responses by this field (L2 §4.2), avoiding descriptor-decoding coupling.
        AgentBusEventType eventType,
        // FEAT-013/014 control plane (P-06): first-class header fields so the request's control descriptor
        // no longer overloads payloadRef. Mirrors ForwardingEnvelope's control fields across the broker
        // hop (the polled BrokerInboundMessage exposes them directly — no descriptor decode
        // from payloadRef). routeHandle is the opaque value (String) — tenantScope is already tenantId.
        // Nullable: control-only / JDBC-back-compat rows carry null; present on data-bearing request/
        // response messages. deadlineMillisEpoch uses Long.MAX_VALUE for "no deadline".
        String traceId,
        String idempotencyKey,
        String routeHandle,
        String capability,
        long deadlineMillisEpoch,
        String inlinePayload,
        // FEAT-013/014 originalCaller (P-06, L2 feat-014 §4): the original gateway/caller serviceId,
        // preserved end-to-end across the relay hop so the runtime can route the response back to the
        // caller (the forward relay overwrites sourceServiceId to itself). A routing/control field, NOT
        // A2A data. Nullable (control-only / JDBC-back-compat).
        String originalCaller
) {
    public BrokerMessageHeaders {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(messageId, "messageId");
        requireNonBlank(sourceServiceId, "sourceServiceId");
        requireNonBlank(targetServiceId, "targetServiceId");
        // payloadRef is the A2A data reference (P-06): conditional — null (control-only) or non-blank.
        // It is NEVER a control-descriptor token; control rides the first-class fields below.
        if (payloadRef != null && payloadRef.isBlank()) {
            throw new IllegalArgumentException("payloadRef must be null or non-blank");
        }
        // correlationId conditional: null (control-only / JDBC back-compat) or non-blank.
        if (correlationId != null && correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must be null or non-blank");
        }
        // eventType: nullable (control-only / JDBC back-compat); no further validation (enum).
        // control-plane fields: null (control-only / back-compat) or non-blank — blank is a wiring error.
        requireNullOrNonBlank(traceId, "traceId");
        requireNullOrNonBlank(idempotencyKey, "idempotencyKey");
        requireNullOrNonBlank(routeHandle, "routeHandle");
        requireNullOrNonBlank(capability, "capability");
        requireNullOrNonBlank(inlinePayload, "inlinePayload");
        requireNullOrNonBlank(originalCaller, "originalCaller");
    }

    /**
     * Back-compat canonical constructor (pre-P-06 7-arg form). The control-plane + inlinePayload
     * fields default to absent (null / no-deadline = {@code Long.MAX_VALUE}). Lets pre-P-06 call
     * sites compile unchanged; new data-bearing code uses the full constructor so control + data
     * each ride their own first-class fields.
     *
     * @param tenantId        tenant scope (mandatory)
     * @param messageId       broker message id
     * @param sourceServiceId source service id
     * @param targetServiceId target service id
     * @param payloadRef      A2A data reference (null for control-only)
     * @param correlationId   cross-hop correlation key (nullable)
     * @param eventType        event-type discriminator (nullable)
     */
    public BrokerMessageHeaders(String tenantId, String messageId, String sourceServiceId,
                                String targetServiceId, String payloadRef, String correlationId,
                                AgentBusEventType eventType) {
        this(tenantId, messageId, sourceServiceId, targetServiceId, payloadRef, correlationId, eventType,
                null, null, null, null, Long.MAX_VALUE, null, null);
    }

    /**
     * Whether this header set carries a payload reference (data-bearing message).
     *
     * @return {@code true} if this header set carries a payload reference (data-bearing);
     *         {@code false} otherwise (control-only)
     */
    public boolean carriesPayloadRef() {
        return payloadRef != null;
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNullOrNonBlank(String value, String name) {
        // null = absent (control-only / back-compat); a blank value is a wiring error, not a valid absence.
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must be null or non-blank");
        }
    }
}
