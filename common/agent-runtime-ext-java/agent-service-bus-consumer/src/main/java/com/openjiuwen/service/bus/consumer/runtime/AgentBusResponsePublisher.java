/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerMessageHeaders;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerOutboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.projection.AgentBusProjectionJsonCodec;

import java.util.Objects;

/**
 * Publishes FEAT-017 response projections through the agent-bus runtime-role producer.
 *
 * @since 2026-07-22
 */
public final class AgentBusResponsePublisher {
    private static final AgentBusProjectionJsonCodec PROJECTION_CODEC = new AgentBusProjectionJsonCodec();

    private final BrokerForwardingProducerPort producer;
    private final String localServiceId;

    /**
     * Creates a new instance.
     *
     * @param producer
     *            runtime-role response producer
     * @param localServiceId
     *            the localServiceId value
     */
    public AgentBusResponsePublisher(BrokerForwardingProducerPort producer, String localServiceId) {
        this.producer = Objects.requireNonNull(producer);
        this.localServiceId = require(localServiceId, "localServiceId");
    }

    /**
     * Publishes a persisted response projection directly to the agent-bus response ingress.
     *
     * @param projection
     *            persisted response projection
     */
    public void publish(BusResponseProjection projection) {
        AgentBusEventType eventType;
        try {
            eventType = AgentBusEventType.valueOf(projection.eventType());
        } catch (IllegalArgumentException unsupported) {
            throw new IllegalStateException("agent-bus does not define projection event type " + projection.eventType(),
                    unsupported);
        }
        String tenantId = require(projection.tenantId(), "tenantId");
        // BusResponseProjection already stores response direction (runtime source, caller target).
        String targetServiceId = require(projection.targetServiceId(), "targetServiceId");
        String routeValue = projection.routeHandle() == null || projection.routeHandle().isBlank()
                ? "response:" + targetServiceId
                : projection.routeHandle();
        long now = projection.occurredAt().toEpochMilli();
        BrokerMessageHeaders headers = new BrokerMessageHeaders(tenantId, projection.eventId(), localServiceId,
                targetServiceId, null, require(projection.correlationId(), "correlationId"), eventType,
                fallback(projection.traceId(), projection.correlationId()),
                fallback(projection.idempotencyKey(), projection.eventId()), routeValue, "agent-runtime-response",
                now + 300_000L, encodeProjection(projection), null);
        BrokerProduceOutcome outcome = producer.produce(
                new BrokerOutboundMessage("target=" + targetServiceId, headers), now);
        if (outcome.outcome() != BrokerProduceOutcome.Outcome.ACCEPTED) {
            throw new IllegalStateException("agent-bus response produce failed: " + outcome.outcome()
                    + ", failureCode=" + outcome.failureCode());
        }
    }

    static String encodeProjection(BusResponseProjection projection) {
        return PROJECTION_CODEC.encode(projection);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? require(fallback, "fallback") : value;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
