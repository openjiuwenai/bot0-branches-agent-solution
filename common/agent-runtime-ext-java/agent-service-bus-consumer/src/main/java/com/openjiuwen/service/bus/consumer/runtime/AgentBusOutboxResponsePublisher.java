/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.port.RuntimeBusResponsePublisher;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Persists FEAT-017 response projections through the canonical agent-bus outbox SDK.
 *
 * @since 2026-07-22
 */
public final class AgentBusOutboxResponsePublisher implements RuntimeBusResponsePublisher {
    private final ForwardingOutboxPort outbox;
    private final String localServiceId;

    /**
     * Creates a new instance.
     *
     * @param outbox
     *            the outbox value
     * @param localServiceId
     *            the localServiceId value
     */
    public AgentBusOutboxResponsePublisher(ForwardingOutboxPort outbox, String localServiceId) {
        this.outbox = Objects.requireNonNull(outbox);
        this.localServiceId = require(localServiceId, "localServiceId");
    }

    /** {@inheritDoc} */
    @Override
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
        ForwardingEnvelope envelope = new ForwardingEnvelope(new ForwardingMessageId(projection.eventId()), eventType,
                tenantId, fallback(projection.traceId(), projection.correlationId()),
                require(projection.correlationId(), "correlationId"),
                fallback(projection.idempotencyKey(), projection.eventId()),
                new ForwardingRouteHandle(routeValue, tenantId), "agent-runtime-response", localServiceId,
                targetServiceId, now + 300_000L, ForwardingEnvelope.PayloadPolicy.DATA_BEARING, null,
                encodeProjection(projection), null);
        outbox.enqueue(envelope, localServiceId, targetServiceId, now);
    }

    private static String encodeProjection(BusResponseProjection projection) {
        StringJoiner descriptor = new StringJoiner(";");
        if (projection.taskId() != null) {
            descriptor.add("taskId=" + projection.taskId());
        }
        descriptor.add("projectionKind=" + projection.projectionKind());
        descriptor.add("revision=" + projection.revision());
        Object taskState = projection.data().get("taskState");
        if (taskState instanceof String state) {
            descriptor.add("status=" + normalizeState(state));
        }
        Object errorCode = projection.data().get("errorCode");
        if (errorCode instanceof String code && !projection.data().containsKey("reason")) {
            descriptor.add("reason=" + code);
        }
        for (Map.Entry<String, Object> entry : projection.data().entrySet()) {
            if (entry.getValue() instanceof String || entry.getValue() instanceof Number
                    || entry.getValue() instanceof Boolean) {
                descriptor.add(entry.getKey() + "=" + entry.getValue());
            }
        }
        return descriptor.toString();
    }

    private static String normalizeState(String state) {
        String prefix = "TASK_STATE_";
        String normalized = state.startsWith(prefix) ? state.substring(prefix.length()) : state;
        return normalized.toLowerCase(Locale.ROOT);
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
