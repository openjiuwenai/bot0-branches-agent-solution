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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Publishes FEAT-017 response projections through the agent-bus runtime-role producer.
 *
 * @since 2026-07-22
 */
public final class AgentBusResponsePublisher {
    private static final Set<String> RESERVED_FIELDS = Set.of("revision", "a2aResponse");

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
        appendA2aResponse(descriptor, projection.data());
        projection.data().entrySet().stream().filter(entry -> !RESERVED_FIELDS.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (entry.getValue() instanceof String || entry.getValue() instanceof Number
                    || entry.getValue() instanceof Boolean) {
                descriptor.add(entry.getKey() + "=" + entry.getValue());
            }
        });
        return descriptor.toString();
    }

    private static void appendA2aResponse(StringJoiner descriptor, Map<String, Object> data) {
        Object response = data.get("a2aResponse");
        if (response == null) {
            return;
        }
        if (!(response instanceof String json) || json.isBlank()) {
            throw new IllegalArgumentException("a2aResponse must be a complete JSON-RPC response");
        }
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        descriptor.add("a2aResponseType=JsonRpcResponse");
        descriptor.add("a2aResponse=" + encoded);
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
