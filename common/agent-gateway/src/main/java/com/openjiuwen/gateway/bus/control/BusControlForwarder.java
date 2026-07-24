/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

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
    private final EnvelopeBuilder envelopeBuilder;
    private final PayloadStore payloadStore;
    private final ForwardingOutboxPort outboxPort;

    /**
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
        if (ctx.rawBody() != null && !ctx.rawBody().isBlank()) {
            payloadRef = payloadStore.stash(ctx.rawBody());
        }
        String idempotencyKey = ctx.messageId() != null && !ctx.messageId().isBlank()
                ? ctx.messageId() : "gw-idem-" + UUID.randomUUID();
        ForwardingEnvelope envelope = envelopeBuilder.buildEnvelope(new EnvelopeBuilder.BuildRequest(
                ctx.tenantId(), ctx.traceId(), idempotencyKey,
                routeHandleValue, targetServiceId, sourceServiceId,
                payloadRef, deadlineMillisEpoch));
        try {
            outboxPort.enqueue(envelope, sourceServiceId, targetServiceId, System.currentTimeMillis());
            return envelope;
        } catch (IllegalStateException | IllegalArgumentException ex) {
            // Outbox adapters map enqueue failures to these types (see FakeForwardingOutboxPort).
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ENQUEUE_FAILED",
                    "Bus enqueue failed", ex);
        }
    }
}
