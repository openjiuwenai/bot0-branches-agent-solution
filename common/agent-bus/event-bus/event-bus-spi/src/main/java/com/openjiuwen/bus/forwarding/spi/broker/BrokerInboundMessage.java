/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi.broker;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import java.util.Objects;

/**
 * Broker-agnostic inbound message returned by {@link BrokerForwardingConsumerPort#poll}
 * (Stage 26, T4 hybrid receiver).
 *
 * <p>Mirrors the routing metadata of {@link BrokerOutboundMessage} without exposing
 * ANY broker product concept (topic / partition / offset) — those are adapter-internal
 * (decision §6.2 ① spirit). The receiver uses {@code tenantId} / {@code messageId} /
 * {@code consumerServiceId} as the inbox dedup key {@code (tenantId, messageId,
 * consumerServiceId)}; {@code payloadRef} (when present) is the HD4 data reference
 * to fetch the payload body externally.
 *
 * <p>{@code consumerServiceId} is the broker consumer-group materialised into the
 * in-flight message at poll time (the polling consumer owns it until commit / reject);
 * {@link BrokerForwardingConsumerPort#commit} / {@link BrokerForwardingConsumerPort#reject}
 * read it back so the adapter advances / replays the right consumer-group offset
 * (model B ack-after-consume, at-least-once).
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-transport-decision.md} (Stage 25 §10 guardrails,
 * Stage 26 broker SPI scaffold).
 */
// scope: forwarding transport.broker — broker-agnostic inbound message; no offset/topic/partition leaked
public record BrokerInboundMessage(
        String tenantId,
        String messageId,
        String sourceServiceId,
        String targetServiceId,
        String consumerServiceId,
        String payloadRef,
        // FEAT-013 cross-hop correlation key (mirrored from headers at poll). Nullable (control-only /
        // JDBC back-compat); the gateway (S2) matches responses by this field (L2 feat-013 §4.2).
        String correlationId,
        // FEAT-013/014 event-type discriminator (mirrored from headers at poll). Nullable; the gateway
        // (S2) classifies responses by this field (L2 §4.2), avoiding descriptor-decoding coupling.
        AgentBusEventType eventType,
        // FEAT-013/014 control plane (P-06): first-class fields mirrored from BrokerMessageHeaders at
        // poll, so the receiver reconstructs the request envelope's control plane DIRECTLY — no
        // no descriptor decode from payloadRef. Mirrors ForwardingEnvelope's control fields.
        // routeHandle is the opaque value (String); tenantScope is tenantId. Nullable (control-only /
        // JDBC back-compat); present on data-bearing request/response messages. deadlineMillisEpoch
        // uses Long.MAX_VALUE for "no deadline".
        String traceId,
        String idempotencyKey,
        String routeHandle,
        String capability,
        long deadlineMillisEpoch,
        String inlinePayload,
        // FEAT-013/014 originalCaller (P-06, L2 feat-014 §4): the original gateway/caller serviceId,
        // preserved across the relay hop so the runtime can route the response back (the forward relay
        // overwrites sourceServiceId to itself). A routing/control field, NOT A2A data. Nullable.
        String originalCaller
) {
    public BrokerInboundMessage {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(messageId, "messageId");
        requireNonBlank(sourceServiceId, "sourceServiceId");
        requireNonBlank(targetServiceId, "targetServiceId");
        requireNonBlank(consumerServiceId, "consumerServiceId");
        // payloadRef is the A2A data reference (P-06): null (control-only) or non-blank — NEVER a
        // control-descriptor token (control rides the first-class fields below).
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
     * Back-compat canonical constructor (pre-P-06 8-arg form). The control-plane + inlinePayload
     * fields default to absent (null / no-deadline = {@code Long.MAX_VALUE}). Lets pre-P-06 call
     * sites compile unchanged; new data-bearing code uses the full constructor so control + data
     * each ride their own first-class fields.
     *
     * @param tenantId         tenant scope (mandatory)
     * @param messageId        broker message id
     * @param sourceServiceId  source service id
     * @param targetServiceId  target service id
     * @param consumerServiceId the polling consumer-group (materialised at poll)
     * @param payloadRef       A2A data reference (null for control-only)
     * @param correlationId    cross-hop correlation key (nullable)
     * @param eventType         event-type discriminator (nullable)
     */
    public BrokerInboundMessage(String tenantId, String messageId, String sourceServiceId,
                                 String targetServiceId, String consumerServiceId, String payloadRef,
                                 String correlationId, AgentBusEventType eventType) {
        this(tenantId, messageId, sourceServiceId, targetServiceId, consumerServiceId, payloadRef,
                correlationId, eventType, null, null, null, null, Long.MAX_VALUE, null, null);
    }

    /**
     * Whether this message carries a payload reference (data-bearing).
     *
     * @return {@code true} if {@code payloadRef} is non-null (a data-bearing message);
     *         {@code false} for a control-only message
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
