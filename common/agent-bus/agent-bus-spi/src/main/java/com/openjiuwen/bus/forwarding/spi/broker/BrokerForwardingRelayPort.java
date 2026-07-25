/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi.broker;

import com.openjiuwen.bus.forwarding.runtime.transport.BrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerOutboundMessage;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;

/**
 * Relay-side broker SPI: produce a claimed outbox record onto a broker (Stage 26,
 * T4 hybrid).
 *
 * <p>The relay worker claims a {@link ForwardingOutboxRecord} from the outbox and
 * calls {@link #produce} to publish it. The implementation derives the broker
 * topic from the record's {@link com.openjiuwen.bus.forwarding.spi.AgentBusEventType
 * eventType} via the injected {@link BrokerTopicResolver} (event-type-driven,
 * decoupled from the opaque {@code routeHandle} — Option B; the adapter never
 * reads {@code routeHandle.value()} for the topic), wraps the record into a
 * {@link BrokerOutboundMessage} (routing descriptor body + routing metadata
 * headers + conditional payloadRef), and publishes to the broker. The opaque
 * {@code routeHandle} rides the record end-to-end as a passthrough field.
 *
 * <p><b>Not a {@code ForwardingDeliveryPort}.</b> {@link #produce} returns a
 * {@link BrokerProduceOutcome}, not a terminal {@code ForwardingDeliveryResult}:
 * a broker produce is fire-and-forget, and {@code ACCEPTED} means the broker
 * accepted the message — NOT that the receiver processed it. The terminal ack
 * (outbox ACKED) arrives via the model-B reverse-ack channel (Stage 27). Stage 26
 * ships this independent SPI; Stage 27 decides how the relay adapter composes
 * with the worker / {@code ForwardingDeliveryPort}.
 *
 * <p>Broker native retry is OFF — the agent-bus retry policy (Stage 14) leads, so
 * a {@link BrokerProduceOutcome.Outcome#UNAVAILABLE UNAVAILABLE} outcome surfaces
 * a retryable failure for the policy to schedule, never a double-retry.
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-transport-decision.md} (Stage 25 adopted-t4 §4 / §10,
 * Stage 26 broker SPI scaffold).
 *
 * @since 0.1.0
 */
// scope: forwarding transport.broker — relay SPI; produce is fire-and-forget, not a terminal delivery
public interface BrokerForwardingRelayPort {
    /**
     * Produce a claimed outbox record onto the broker.
     *
     * @param record the claimed outbox record (non-null; the adapter reads only its
     *               routing metadata — tenantId / messageId / sourceServiceId /
     *               targetServiceId / payloadRef / eventType — and derives the topic
     *               via {@link BrokerTopicResolver} from the eventType, never
     *               unwrapping the opaque routeHandle)
     * @param nowMillisEpoch the produce instant (for adapter-internal sequencing / observability)
     * @return the produce outcome (ACCEPTED / UNAVAILABLE / ROUTE_NOT_FOUND); never
     *         a terminal delivery result
     */
    BrokerProduceOutcome produce(ForwardingOutboxRecord record, long nowMillisEpoch);

    /**
     * Produce a pre-built broker-agnostic message directly onto the broker — the
     * non-outbox (direct-tap) produce entry. Used by a FEAT-017 target runtime's
     * response producer that taps {@code AgentEmitter} callbacks and emits
     * {@code INVOCATION_*}/{@code A2A_CALL_*} response events to {@code resp_in}
     * WITHOUT a durable outbox record (FEAT-017 §5.1.4: ack at the receive boundary;
     * a response-publish failure is recovered via TaskStore / status projection / GetTask).
     *
     * <p>The implementation derives the broker topic from
     * {@code message.headers().eventType()} via the injected {@link BrokerTopicResolver}
     * (+ hop suffix), wraps the message into a broker-native message (routing-descriptor
     * body + first-class control-plane headers + conditional {@code payloadRef} + bounded
     * {@code inlinePayload}), and publishes. A {@code null} {@code eventType} → non-retryable
     * {@link BrokerProduceOutcome.Outcome#ROUTE_NOT_FOUND}.
     *
     * <p><b>Optional capability.</b> A relay port that only ever re-publishes claimed outbox
     * records (the event-bus relay, or an outbox-only test fake) does NOT need direct-tap;
     * the default throws {@link UnsupportedOperationException} so such ports compile
     * unchanged. The RocketMQ adapter + the in-memory broker override it; a direct-tap
     * producer injects an overriding port (e.g. a {@code RocketMqBrokerForwardingRelay}
     * bound to the {@code resp_in} suffix). Precedent: {@link java.util.Iterator#remove()}.
     *
     * @param message the pre-built broker-agnostic outbound message (non-null; the adapter
     *                reads only its routing descriptor + headers and derives the topic via
     *                {@link BrokerTopicResolver}, never unwrapping the opaque routeHandle)
     * @param nowMillisEpoch the produce instant (adapter-internal sequencing / observability)
     * @return the produce outcome (ACCEPTED / UNAVAILABLE / ROUTE_NOT_FOUND)
     * @since 0.1.0
     */
    default BrokerProduceOutcome produce(BrokerOutboundMessage message, long nowMillisEpoch) {
        throw new UnsupportedOperationException(
                "direct (non-outbox) produce is not supported by this BrokerForwardingRelayPort; "
                        + "override produce(BrokerOutboundMessage, long) to enable FEAT-017 direct-tap");
    }
}
