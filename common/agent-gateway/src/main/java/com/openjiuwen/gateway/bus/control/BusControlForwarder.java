package com.openjiuwen.gateway.bus.control;

import java.util.UUID;

import org.springframework.http.HttpStatus;

import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;

/** I-04 outbound: build envelope + enqueue (FEAT-012 §4.4 P3). Does NOT call I-03.
 *  Not a @Component yet — wired by B4 facade when the ForwardingOutboxPort bean is available. */
public class BusControlForwarder {
    private final EnvelopeBuilder envelopeBuilder;
    private final PayloadStore payloadStore;
    private final ForwardingOutboxPort outboxPort;

    public BusControlForwarder(EnvelopeBuilder envelopeBuilder, PayloadStore payloadStore,
                               ForwardingOutboxPort outboxPort) {
        this.envelopeBuilder = envelopeBuilder;
        this.payloadStore = payloadStore;
        this.outboxPort = outboxPort;
    }

    public ForwardingReceipt forward(GovernanceContext ctx, String routeHandleValue,
                                     String targetServiceId, String sourceServiceId,
                                     long deadlineMillisEpoch) {
        String payloadRef = null;
        if (ctx.rawBody() != null && !ctx.rawBody().isBlank()) {
            payloadRef = payloadStore.stash(ctx.rawBody());
        }
        String idempotencyKey = ctx.messageId() != null && !ctx.messageId().isBlank()
                ? ctx.messageId() : "gw-idem-" + UUID.randomUUID();
        ForwardingEnvelope envelope = envelopeBuilder.buildEnvelope(
                ctx.tenantId(), ctx.traceId(), idempotencyKey,
                routeHandleValue, targetServiceId, sourceServiceId,
                payloadRef, deadlineMillisEpoch);
        try {
            return outboxPort.enqueue(envelope, sourceServiceId, targetServiceId, System.currentTimeMillis());
        } catch (Exception ex) {
            throw new GovernanceException(HttpStatus.SERVICE_UNAVAILABLE, "ENQUEUE_FAILED",
                    "Bus enqueue failed", ex);
        }
    }
}
