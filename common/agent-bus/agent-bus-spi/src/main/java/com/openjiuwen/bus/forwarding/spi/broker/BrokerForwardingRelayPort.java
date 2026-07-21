/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi.broker;

import com.openjiuwen.bus.forwarding.runtime.transport.BrokerTopicResolver;
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
}
