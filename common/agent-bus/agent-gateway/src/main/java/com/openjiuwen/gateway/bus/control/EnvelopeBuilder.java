/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Builds the ForwardingEnvelope for BUS I-04 outbound (FEAT-012 §4.4 P3).
 *
 * @since 2026-07-24
 */
@Component
public class EnvelopeBuilder {
    /**
     * Inputs for {@link #buildEnvelope(BuildRequest)} (ICD §4.4 P3 fields).
     *
     * @param tenantId tenant scope
     * @param traceId trace id
     * @param idempotencyKey client message id or generated key
     * @param routeHandleValue opaque route handle from RDC
     * @param targetServiceId target service from discovery
     * @param sourceServiceId gateway service identity
     * @param payloadRef optional stashed body ref
     * @param deadlineMillisEpoch enqueue deadline (epoch millis)
     */
    public record BuildRequest(
            String tenantId,
            String traceId,
            String idempotencyKey,
            String routeHandleValue,
            String targetServiceId,
            String sourceServiceId,
            String payloadRef,
            long deadlineMillisEpoch) {
    }

    /**
     * Builds a CLIENT_INVOCATION_REQUESTED envelope with no inline body (large payloads take payloadRef).
     *
     * @param request envelope field bundle
     * @return the forwarding envelope ready for outbox enqueue
     */
    public ForwardingEnvelope buildEnvelope(BuildRequest request) {
        return buildEnvelope(request, null);
    }

    /**
     * Builds a CLIENT_INVOCATION_REQUESTED envelope, optionally inlining a small A2A body.
     *
     * <p>P-06 (2b): a bounded small body may ride {@code inlinePayload} so a separate-process consumer reads
     * it directly — the in-process {@code payloadRef} stash is unreachable across processes. Large / multimodal
     * payloads still take the {@code payloadRef} reference path (2a, caller-owned store).
     *
     * <p>P-06 (L2 feat-014 §4): {@code originalCaller} is set to the gateway's {@code sourceServiceId} so the
     * runtime routes the response back across the relay hop to this gateway (the forward relay overwrites
     * {@code sourceServiceId} to itself; without {@code originalCaller} the response targets the relay and the
     * gateway never receives it → UNKNOWN).
     *
     * @param request       envelope field bundle
     * @param inlinePayload bounded small A2A body (null for control-only / large-takes-ref)
     * @return the forwarding envelope ready for outbox enqueue
     */
    public ForwardingEnvelope buildEnvelope(BuildRequest request, String inlinePayload) {
        String correlationId = "gw-correlation-" + UUID.randomUUID();
        ForwardingMessageId messageId = new ForwardingMessageId("gw-" + UUID.randomUUID());
        ForwardingRouteHandle routeHandle = new ForwardingRouteHandle(
                request.routeHandleValue(), request.tenantId());
        boolean hasData = (request.payloadRef() != null)
                || (inlinePayload != null && !inlinePayload.isBlank());
        ForwardingEnvelope.PayloadPolicy policy = hasData
                ? ForwardingEnvelope.PayloadPolicy.DATA_BEARING
                : ForwardingEnvelope.PayloadPolicy.CONTROL_ONLY;
        return new ForwardingEnvelope(
                messageId, AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                request.tenantId(), request.traceId(), correlationId, request.idempotencyKey(),
                routeHandle, "client-invocation", request.sourceServiceId(), request.targetServiceId(),
                request.deadlineMillisEpoch(), policy, request.payloadRef(), inlinePayload,
                request.sourceServiceId());
    }
}
