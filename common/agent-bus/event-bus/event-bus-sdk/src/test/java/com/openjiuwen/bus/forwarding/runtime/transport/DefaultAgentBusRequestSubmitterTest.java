/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerMessageHeaders;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerOutboundMessage;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.InMemoryBroker;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.AgentBusRequestSubmitter;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;

import org.junit.jupiter.api.Test;

/**
 * TDD for {@link DefaultAgentBusRequestSubmitter} (P-14): the high-level Runtime caller
 * submit port maps a {@link ForwardingEnvelope} to a {@link BrokerOutboundMessage} and
 * directly produces via the caller-role {@code requestProducer} (which owns the
 * {@code "req"} topic suffix) — no JDBC outbox. Pins the envelope→message mapping, the
 * {@code ascend_bus_a2a_req} topic, and the {@link BrokerProduceOutcome}→{@link ForwardingReceipt}
 * mapping (ACCEPTED / UNAVAILABLE / ROUTE_NOT_FOUND).
 *
 * @since 0.1.0
 */
class DefaultAgentBusRequestSubmitterTest {
    private final InMemoryBroker broker = new InMemoryBroker(new DefaultBrokerTopicResolver(), "req");
    private final AgentBusRequestSubmitter submitter = new DefaultAgentBusRequestSubmitter(broker);

    private static ForwardingEnvelope a2aCallEnvelope() {
        return new ForwardingEnvelope(
                new ForwardingMessageId("msg-1"),
                AgentBusEventType.A2A_CALL_REQUESTED,
                "tenant-a", "trace-1", "corr-1", "idem-1",
                new ForwardingRouteHandle("route-1", "tenant-a"),
                "cap-1", "src-1", "tgt-1", 999_999L,
                ForwardingEnvelope.PayloadPolicy.DATA_BEARING, null, "inline-body", "src-1");
    }

    @Test
    void submitProducesToRequestTopicAndReturnsAcceptedReceipt() {
        ForwardingEnvelope env = a2aCallEnvelope();
        ForwardingReceipt receipt = submitter.submit(env);

        assertThat(receipt.accepted()).isTrue();
        assertThat(receipt.messageId()).isEqualTo(env.messageId());
        assertThat(receipt.tenantId()).isEqualTo(env.tenantId());

        // landed on the caller-role req topic (A2A_* -> "a2a" family + "req" suffix)
        assertThat(broker.messageCount("ascend_bus_a2a_req")).isEqualTo(1);
        // the queued message mirrors the envelope 1:1 (headers + routing descriptor)
        BrokerOutboundMessage sent = broker.outboundMessage(env.tenantId(), env.messageId().value()).orElseThrow();
        assertThat(sent.routingDescriptor()).isEqualTo("target=" + env.targetServiceId());
        BrokerMessageHeaders h = sent.headers();
        assertThat(h.tenantId()).isEqualTo(env.tenantId());
        assertThat(h.messageId()).isEqualTo(env.messageId().value());
        assertThat(h.sourceServiceId()).isEqualTo(env.sourceServiceId());
        assertThat(h.targetServiceId()).isEqualTo(env.targetServiceId());
        assertThat(h.payloadRef()).isEqualTo(env.payloadRef());
        assertThat(h.correlationId()).isEqualTo(env.correlationId());
        assertThat(h.eventType()).isEqualTo(env.eventType());
        assertThat(h.traceId()).isEqualTo(env.traceId());
        assertThat(h.idempotencyKey()).isEqualTo(env.idempotencyKey());
        assertThat(h.routeHandle()).isEqualTo(env.routeHandle().value());
        assertThat(h.capability()).isEqualTo(env.capability());
        assertThat(h.deadlineMillisEpoch()).isEqualTo(env.deadlineMillisEpoch());
        assertThat(h.inlinePayload()).isEqualTo(env.inlinePayload());
        assertThat(h.originalCaller()).isEqualTo(env.originalCaller());
    }

    @Test
    void submitReturnsRejectedReceiptWhenBrokerUnavailable() {
        broker.setUnavailable(true);

        ForwardingReceipt receipt = submitter.submit(a2aCallEnvelope());

        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.failureCode()).isEqualTo(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
        assertThat(broker.messageCount("ascend_bus_a2a_req")).isZero();
    }

    @Test
    void submitReturnsRejectedReceiptOnRouteNotFound() {
        // InMemoryBroker can't synthesize ROUTE_NOT_FOUND for a valid (non-null eventType) envelope,
        // so exercise the submitter's outcome->receipt switch with a stub producer that returns it.
        BrokerForwardingProducerPort routeNotFoundProducer = new BrokerForwardingProducerPort() {
            @Override
            public BrokerProduceOutcome produce(BrokerOutboundMessage message, long nowMillisEpoch) {
                return BrokerProduceOutcome.routeNotFound(ForwardingFailureCode.ROUTE_NOT_FOUND);
            }

            @Override
            public BrokerProduceOutcome produce(ForwardingOutboxRecord record, long nowMillisEpoch) {
                throw new UnsupportedOperationException("outbox-record produce not used by the submitter");
            }
        };
        AgentBusRequestSubmitter s = new DefaultAgentBusRequestSubmitter(routeNotFoundProducer);

        ForwardingReceipt receipt = s.submit(a2aCallEnvelope());

        assertThat(receipt.accepted()).isFalse();
        assertThat(receipt.failureCode()).isEqualTo(ForwardingFailureCode.ROUTE_NOT_FOUND);
    }
}
