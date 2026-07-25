/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

import java.util.Objects;

/**
 * Runtime-to-runtime forwarding envelope for the C3 outbox / inbox substrate.
 *
 * <p>Mirrors the Forwarding Envelope Required Fields of
 * {@code ICD-Agent-Bus-Forwarding} (HD4): tenantId, traceId, correlationId,
 * idempotencyKey, routeHandle, capability, deadline. {@code payloadRef} is
 * conditionally required (MI5-003 option B): mandatory when
 * {@link PayloadPolicy#DATA_BEARING}, optional for
 * {@link PayloadPolicy#CONTROL_ONLY}.
 *
 * <p>Bounded-payload invariant (HD4, P-06): this envelope NEVER carries a LARGE
 * payload body, a token stream, or Task execution state — large / multimodal
 * payloads take the {@code payloadRef} data reference path (HD4). A bounded SMALL
 * body may ride {@code inlinePayload} (2b); both are DATA channels, never a
 * control-descriptor token — the control plane rides the envelope's first-class
 * control fields (traceId / idempotencyKey / routeHandle / capability / deadline).
 * The compact constructor additionally enforces tenant continuity: the envelope
 * {@code tenantId} must equal {@link ForwardingRouteHandle#tenantScope()}, else
 * {@link ForwardingFailureCode#TENANT_MISMATCH}.
 *
 * <p>FEAT-013+014 additive extension: {@code eventType} discriminates the
 * invocation / A2A-call event family (see {@link AgentBusEventType});
 * {@code sourceServiceId} / {@code targetServiceId} lift the source / target
 * route references onto the envelope so it is self-describing — they remain
 * mirrored on {@link ForwardingOutboxRecord}, with the envelope authoritative
 * (feat-013 §2.3.1). The compact constructor validates all three as non-null
 * and non-blank.
 *
 * <p>Authority: {@code ICD-Agent-Bus-Forwarding} (HD4);
 * {@code ICD-Agent-Bus-Forwarding-Runtime};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §5/§6}.
 */
// scope: forwarding substrate — control + payloadRef only; never a payload body
public record ForwardingEnvelope(
        ForwardingMessageId messageId,
        AgentBusEventType eventType,
        String tenantId,
        String traceId,
        String correlationId,
        String idempotencyKey,
        ForwardingRouteHandle routeHandle,
        String capability,
        String sourceServiceId,
        String targetServiceId,
        long deadlineMillisEpoch,
        PayloadPolicy payloadPolicy,
        String payloadRef,
        // P-06 (2b): bounded small inline body for small JSON-RPC payloads. Large / multimodal payloads
        // still take the payloadRef data reference path (2a, caller-owned store). Never a control token.
        String inlinePayload,
        // P-06 (L2 feat-014 §4): originalCaller — the original gateway/caller serviceId, preserved
        // end-to-end so the runtime can route the response back across the relay hop (the forward relay
        // overwrites sourceServiceId to itself). A routing/control field, NOT A2A data. Nullable.
        String originalCaller
) {
    public ForwardingEnvelope {
        Objects.requireNonNull(messageId, "messageId is required");
        Objects.requireNonNull(eventType, "eventType is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Objects.requireNonNull(traceId, "traceId is required");
        if (traceId.isBlank()) {
            throw new IllegalArgumentException("traceId must not be blank");
        }
        Objects.requireNonNull(correlationId, "correlationId is required");
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must not be blank");
        }
        Objects.requireNonNull(idempotencyKey, "idempotencyKey is required");
        if (idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
        Objects.requireNonNull(routeHandle, "routeHandle is required");
        Objects.requireNonNull(capability, "capability is required");
        if (capability.isBlank()) {
            throw new IllegalArgumentException("capability must not be blank");
        }
        Objects.requireNonNull(sourceServiceId, "sourceServiceId is required");
        if (sourceServiceId.isBlank()) {
            throw new IllegalArgumentException("sourceServiceId must not be blank");
        }
        Objects.requireNonNull(targetServiceId, "targetServiceId is required");
        if (targetServiceId.isBlank()) {
            throw new IllegalArgumentException("targetServiceId must not be blank");
        }
        Objects.requireNonNull(payloadPolicy, "payloadPolicy is required");
        // P-06: tenant continuity + data-channel invariants (payloadRef / inlinePayload / originalCaller)
        // — extracted so the compact constructor stays under the 50-line method limit (G.MET.01).
        validateTenant(tenantId, routeHandle);
        validatePayload(payloadPolicy, payloadRef, inlinePayload, originalCaller);
    }

    private static void validateTenant(String tenantId, ForwardingRouteHandle routeHandle) {
        // tenant isolation: envelope tenant must equal the route's tenant scope (R-C.c)
        if (!tenantId.equals(routeHandle.tenantScope())) {
            throw new IllegalArgumentException(
                    "tenant_mismatch: envelope tenantId '" + tenantId
                    + "' must equal routeHandle tenantScope '" + routeHandle.tenantScope() + "'");
        }
    }

    private static void validatePayload(PayloadPolicy payloadPolicy, String payloadRef,
            String inlinePayload, String originalCaller) {
        // P-06: data reference (payloadRef) and bounded inline body (inlinePayload) are SEPARATE data
        // channels — neither is a control-descriptor token. DATA_BEARING requires AT LEAST ONE of them.
        if (payloadPolicy == PayloadPolicy.DATA_BEARING) {
            boolean hasRef = payloadRef != null && !payloadRef.isBlank();
            boolean hasInline = inlinePayload != null && !inlinePayload.isBlank();
            if (!hasRef && !hasInline) {
                throw new IllegalArgumentException(
                        "DATA_BEARING message requires payloadRef or inlinePayload");
            }
        }
        if (payloadRef != null && payloadRef.isBlank()) {
            throw new IllegalArgumentException("payloadRef must be null or non-blank");
        }
        // inlinePayload: null (absent / large-payload-takes-ref) or non-blank — blank is a wiring error.
        if (inlinePayload != null && inlinePayload.isBlank()) {
            throw new IllegalArgumentException("inlinePayload must be null or non-blank");
        }
        // originalCaller: null (absent / single-hop) or non-blank — blank is a wiring error.
        if (originalCaller != null && originalCaller.isBlank()) {
            throw new IllegalArgumentException("originalCaller must be null or non-blank");
        }
    }

    /**
     * Back-compat canonical constructor (pre-P-06 13-arg form): inlinePayload defaults to null. Lets
     * pre-P-06 call sites compile unchanged; new data-bearing code uses the full constructor so a small
     * body can inline (2b) while large payloads take the payloadRef reference path (2a, caller-owned).
     *
     * @param messageId          message id
     * @param eventType          event type
     * @param tenantId           tenant scope
     * @param traceId            W3C trace id
     * @param correlationId      cross-hop correlation key
     * @param idempotencyKey     client retry idempotency key
     * @param routeHandle        opaque typed route handle
     * @param capability         capability identifier
     * @param sourceServiceId    source service id
     * @param targetServiceId    target service id
     * @param deadlineMillisEpoch absolute deadline, or {@code Long.MAX_VALUE} for none
     * @param payloadPolicy      CONTROL_ONLY or DATA_BEARING
     * @param payloadRef         A2A data reference (null for control-only)
     */
    public ForwardingEnvelope(ForwardingMessageId messageId, AgentBusEventType eventType, String tenantId,
                              String traceId, String correlationId, String idempotencyKey,
                              ForwardingRouteHandle routeHandle, String capability, String sourceServiceId,
                              String targetServiceId, long deadlineMillisEpoch,
                              PayloadPolicy payloadPolicy, String payloadRef) {
        this(messageId, eventType, tenantId, traceId, correlationId, idempotencyKey, routeHandle,
                capability, sourceServiceId, targetServiceId, deadlineMillisEpoch, payloadPolicy,
                payloadRef, null, null);
    }

    /**
     * Back-compat constructor (P-06 §1 + inlinePayload, no originalCaller): originalCaller defaults to
     * null. For responses / single-hop requests that don't carry an originalCaller. Callers that need to
     * preserve the original caller across a relay hop use the full constructor.
     *
     * @param messageId          message id
     * @param eventType          event type
     * @param tenantId           tenant scope
     * @param traceId            W3C trace id
     * @param correlationId      cross-hop correlation key
     * @param idempotencyKey     client retry idempotency key
     * @param routeHandle        opaque typed route handle
     * @param capability         capability identifier
     * @param sourceServiceId    source service id
     * @param targetServiceId    target service id
     * @param deadlineMillisEpoch absolute deadline, or {@code Long.MAX_VALUE} for none
     * @param payloadPolicy      CONTROL_ONLY or DATA_BEARING
     * @param payloadRef         A2A data reference (null for control-only)
     * @param inlinePayload      bounded small inline body (null for control-only / large-takes-ref)
     */
    public ForwardingEnvelope(ForwardingMessageId messageId, AgentBusEventType eventType, String tenantId,
                              String traceId, String correlationId, String idempotencyKey,
                              ForwardingRouteHandle routeHandle, String capability, String sourceServiceId,
                              String targetServiceId, long deadlineMillisEpoch,
                              PayloadPolicy payloadPolicy, String payloadRef, String inlinePayload) {
        this(messageId, eventType, tenantId, traceId, correlationId, idempotencyKey, routeHandle,
                capability, sourceServiceId, targetServiceId, deadlineMillisEpoch, payloadPolicy,
                payloadRef, inlinePayload, null);
    }

    /**
     * Whether this envelope carries a payload reference (data-bearing message).
     *
     * @return {@code true} if this envelope carries a payload reference; {@code false} otherwise
     */
    public boolean carriesPayloadRef() {
        return payloadRef != null;
    }

    /**
     * Payload presence policy — encodes the MI5-003 option B conditional
     * requirement for {@code payloadRef}.
     */
    public enum PayloadPolicy {
        /** Pure control message; payloadRef optional (typically absent). */
        CONTROL_ONLY,
        /** Carries external data / a large payload; payloadRef mandatory. */
        DATA_BEARING
    }
}
