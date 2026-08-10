/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport;

import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerMessageHeaders;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerOutboundMessage;
import com.openjiuwen.bus.forwarding.spi.AgentBusRequestSubmitter;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;

import java.util.Objects;

/**
 * SDK default {@link AgentBusRequestSubmitter}: maps a {@link ForwardingEnvelope} to a
 * {@link BrokerOutboundMessage} and directly produces via the caller-role
 * {@code requestProducer} (which owns the {@code "req"} topic suffix — it resolves the
 * topic internally, so this submitter does not). No JDBC outbox — the broker's acceptance
 * is the reliability handoff boundary (P-14). Mirrors the runtime-role
 * {@code AgentBusResponsePublisher} direct-tap pattern.
 *
 * @since 0.1.0
 */
public final class DefaultAgentBusRequestSubmitter implements AgentBusRequestSubmitter {
    private final BrokerForwardingProducerPort requestProducer;

    /**
     * Construct with the caller-role request producer (suffix {@code "req"}).
     *
     * @param requestProducer the caller-role hop1 request producer
     */
    public DefaultAgentBusRequestSubmitter(BrokerForwardingProducerPort requestProducer) {
        this.requestProducer = Objects.requireNonNull(requestProducer, "requestProducer is required");
    }

    @Override
    public ForwardingReceipt submit(ForwardingEnvelope envelope) {
        long now = System.currentTimeMillis();
        BrokerMessageHeaders headers = new BrokerMessageHeaders(
                envelope.tenantId(), envelope.messageId().value(), envelope.sourceServiceId(),
                envelope.targetServiceId(), envelope.payloadRef(), envelope.correlationId(),
                envelope.eventType(), envelope.traceId(), envelope.idempotencyKey(),
                envelope.routeHandle().value(), envelope.capability(), envelope.deadlineMillisEpoch(),
                envelope.inlinePayload(), envelope.originalCaller());
        BrokerOutboundMessage message = new BrokerOutboundMessage(
                "target=" + envelope.targetServiceId(), headers);
        BrokerProduceOutcome outcome = requestProducer.produce(message, now);
        return toReceipt(envelope, outcome, now);
    }

    private static ForwardingReceipt toReceipt(ForwardingEnvelope e, BrokerProduceOutcome o, long now) {
        return switch (o.outcome()) {
            case ACCEPTED -> ForwardingReceipt.accepted(e.messageId(), e.tenantId(), now);
            case UNAVAILABLE -> ForwardingReceipt.rejected(e.messageId(), e.tenantId(),
                    failureCode(o, ForwardingFailureCode.RECEIVER_UNAVAILABLE), now);
            case ROUTE_NOT_FOUND -> ForwardingReceipt.rejected(e.messageId(), e.tenantId(),
                    failureCode(o, ForwardingFailureCode.ROUTE_NOT_FOUND), now);
        };
    }

    private static ForwardingFailureCode failureCode(BrokerProduceOutcome o, ForwardingFailureCode fallback) {
        return o.failureCode() != null ? o.failureCode() : fallback;
    }
}
