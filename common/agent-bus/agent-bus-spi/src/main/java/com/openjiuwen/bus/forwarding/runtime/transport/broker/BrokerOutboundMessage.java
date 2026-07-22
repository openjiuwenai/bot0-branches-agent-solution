/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker;

import java.util.Objects;

/**
 * Broker-agnostic outbound message produced by the relay (Stage 26, T4 hybrid).
 *
 * <p>Composed of a {@link BrokerMessageHeaders headers} block (routing metadata +
 * conditional payloadRef) and a {@code routingDescriptor} body. The body carries
 * ONLY a short routing descriptor — NEVER the payload body, a token stream, or
 * Task execution state (decision §6.2 ②). The payload is referenced externally
 * via {@code headers.payloadRef} (HD4 data-reference path), not embedded.
 *
 * <p>The relay builds this from a {@code ForwardingOutboxRecord} (the durable
 * claim form) without ever unwrapping the opaque {@code routeHandle} (HD4): the
 * broker topic is resolved by the injected {@code ForwardingEndpointResolver}, and
 * this message carries only routing metadata + descriptor.
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-forwarding-runtime-transport-decision.md} (Stage 25 §10 guardrails,
 * Stage 26 broker SPI scaffold).
 */
// scope: forwarding transport.broker — broker-agnostic outbound message; body is routing descriptor only
public record BrokerOutboundMessage(String routingDescriptor, BrokerMessageHeaders headers) {
    public BrokerOutboundMessage {
        Objects.requireNonNull(routingDescriptor, "routingDescriptor is required");
        Objects.requireNonNull(headers, "headers is required");
    }
}
