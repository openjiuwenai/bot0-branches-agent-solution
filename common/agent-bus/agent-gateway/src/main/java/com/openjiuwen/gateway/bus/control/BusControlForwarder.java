/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * I-04 outbound: build envelope + enqueue (FEAT-012 §4.4 P3). Does not call I-03.
 * Not a {@code @Component} yet — wired by B4 facade when the ForwardingOutboxPort bean is available.
 *
 * @since 2026-07-24
 */
public class BusControlForwarder {
    private static final ObjectMapper mapper = new ObjectMapper();
    private final EnvelopeBuilder envelopeBuilder;
    private final PayloadStore payloadStore;
    private final ForwardingOutboxPort outboxPort;

    /**
     * Creates a forwarder with envelope, stash, and outbox dependencies.
     *
     * @param envelopeBuilder envelope factory
     * @param payloadStore body stash for HD4 payload refs
     * @param outboxPort durable outbox port
     */
    public BusControlForwarder(EnvelopeBuilder envelopeBuilder, PayloadStore payloadStore,
                               ForwardingOutboxPort outboxPort) {
        this.envelopeBuilder = envelopeBuilder;
        this.payloadStore = payloadStore;
        this.outboxPort = outboxPort;
    }

    /**
     * Stashes body (when present), builds envelope, and enqueues to the outbox.
     *
     * @param ctx governance context
     * @param routeHandleValue opaque route handle
     * @param targetServiceId target service from RDC
     * @param sourceServiceId gateway service identity
     * @param deadlineMillisEpoch enqueue deadline (epoch millis)
     * @return the enqueued envelope (includes correlation id for projection poll)
     * @throws GovernanceException when outbox enqueue fails
     */
    public ForwardingEnvelope forward(GovernanceContext ctx, String routeHandleValue,
                                     String targetServiceId, String sourceServiceId,
                                     long deadlineMillisEpoch) {
        String payloadRef = null;
        String inlinePayload = null;
        if (ctx.rawBody() != null && !ctx.rawBody().isBlank()) {
            // P-06 2b: inline the small A2A body so a separate-process consumer reads it directly.
            // Do NOT stash a process-internal payloadRef — the consumer's BusEnvelopeValidator rejects
            // a non-null payloadRef that isn't a valid external reference (PAYLOAD_REFERENCE_INVALID).
            // v0830: inject authoritative tenant into params.tenant (runtime expects params.tenant,
            // not params.metadata.tenantId). Envelope tenantId stays authoritative (validateTenant).
            inlinePayload = injectTenantIntoBody(ctx.rawBody(), ctx.tenantId());
        }
        String idempotencyKey = ctx.messageId() != null && !ctx.messageId().isBlank()
                ? ctx.messageId() : "gw-idem-" + UUID.randomUUID();
        ForwardingEnvelope envelope = envelopeBuilder.buildEnvelope(new EnvelopeBuilder.BuildRequest(
                ctx.tenantId(), ctx.traceId(), idempotencyKey,
                routeHandleValue, targetServiceId, sourceServiceId,
                payloadRef, deadlineMillisEpoch), inlinePayload);
        try {
            outboxPort.enqueue(envelope, sourceServiceId, targetServiceId, System.currentTimeMillis());
            return envelope;
        } catch (IllegalStateException | IllegalArgumentException ex) {
            // Outbox adapters map enqueue failures to these types (see FakeForwardingOutboxPort).
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ENQUEUE_FAILED",
                    "Bus enqueue failed", ex);
        }
    }

    /**
     * Forward a QUERY (GetTask) control event via the bus (v0830 S6 bus path).
     * Uses CLIENT_INVOCATION_QUERY_REQUESTED event type. Query events don't go through
     * G4 admission; idempotencyKey is optional (bus dedup via messageId).
     *
     * @param ctx governance context
     * @param routeHandleValue opaque route handle
     * @param targetServiceId target service from RDC
     * @param sourceServiceId gateway service identity
     * @param deadlineMillisEpoch enqueue deadline
     * @return the enqueued envelope (includes correlation id for projection poll)
     */
    public ForwardingEnvelope forwardQuery(GovernanceContext ctx, String routeHandleValue,
                                           String targetServiceId, String sourceServiceId,
                                           long deadlineMillisEpoch) {
        String inlinePayload = null;
        if (ctx.rawBody() != null && !ctx.rawBody().isBlank()) {
            inlinePayload = injectTenantIntoBody(ctx.rawBody(), ctx.tenantId());
        }
        // Query events carry no client message id; synthesize a key so the ForwardingEnvelope
        // SPI's non-null idempotencyKey contract holds (bus query dedup is via messageId, not this key).
        String idempotencyKey = "gw-query-" + UUID.randomUUID();
        ForwardingEnvelope envelope = envelopeBuilder.buildEnvelope(new EnvelopeBuilder.BuildRequest(
                ctx.tenantId(), ctx.traceId(), idempotencyKey,
                routeHandleValue, targetServiceId, sourceServiceId,
                null, deadlineMillisEpoch), inlinePayload,
                com.openjiuwen.bus.forwarding.spi.AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED);
        try {
            outboxPort.enqueue(envelope, sourceServiceId, targetServiceId, System.currentTimeMillis());
            return envelope;
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ENQUEUE_FAILED",
                    "Bus query enqueue failed", ex);
        }
    }

    /**
     * Forward a SUBSCRIBE (SubscribeToTask) control event via the bus (v0830 S8 bus path).
     * Uses CLIENT_STREAM_SUBSCRIBE_REQUESTED event type. Returns the envelope so the
     * caller can poll for STREAM_READY (carrying streamRef for I-06 bridge).
     *
     * @param ctx governance context
     * @param routeHandleValue opaque route handle
     * @param targetServiceId target service from RDC
     * @param sourceServiceId gateway service identity
     * @param deadlineMillisEpoch enqueue deadline
     * @return the enqueued envelope (includes correlation id for projection poll)
     */
    public ForwardingEnvelope forwardSubscribe(GovernanceContext ctx, String routeHandleValue,
                                               String targetServiceId, String sourceServiceId,
                                               long deadlineMillisEpoch) {
        String inlinePayload = null;
        if (ctx.rawBody() != null && !ctx.rawBody().isBlank()) {
            inlinePayload = injectTenantIntoBody(ctx.rawBody(), ctx.tenantId());
        }
        // Subscribe events carry no client message id; synthesize a key (same rationale as forwardQuery).
        String idempotencyKey = "gw-subscribe-" + UUID.randomUUID();
        ForwardingEnvelope envelope = envelopeBuilder.buildEnvelope(new EnvelopeBuilder.BuildRequest(
                ctx.tenantId(), ctx.traceId(), idempotencyKey,
                routeHandleValue, targetServiceId, sourceServiceId,
                null, deadlineMillisEpoch), inlinePayload,
                com.openjiuwen.bus.forwarding.spi.AgentBusEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED);
        try {
            outboxPort.enqueue(envelope, sourceServiceId, targetServiceId, System.currentTimeMillis());
            return envelope;
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ENQUEUE_FAILED",
                    "Bus subscribe enqueue failed", ex);
        }
    }

    /**
     * Inject the authoritative tenant into {@code params.tenant} of the A2A body
     * before inlining it into the envelope's inlinePayload (v0830 — runtime expects
     * {@code params.tenant}). Returns the original body if parsing fails or the body
     * is not a JSON object.
     *
     * @param rawBody  original client A2A JSON-RPC body
     * @param tenantId authoritative tenant from G2
     * @return body with {@code params.tenant} set, or the original body on failure
     */
    private static String injectTenantIntoBody(String rawBody, String tenantId) {
        try {
            var root = mapper.readTree(rawBody);
            if (root.isObject()) {
                var params = (com.fasterxml.jackson.databind.node.ObjectNode) root.path("params");
                params.put("tenant", tenantId);
                return mapper.writeValueAsString(root);
            }
            return rawBody;
        } catch (Exception ex) {
            // parse failure — return raw body (envelope tenantId still authoritative)
            return rawBody;
        }
    }
}
