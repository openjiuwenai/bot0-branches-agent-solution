/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;
import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapts the canonical agent-bus consumer SPI to the FEAT-017 runtime delivery boundary.
 *
 * @since 2026-07-22
 */
public final class AgentBusBrokerDeliveryPort implements AutoCloseable {
    private static final Set<AgentBusEventType> REQUEST_TYPES = Set.of(AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
            AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED, AgentBusEventType.CLIENT_STREAM_SUBSCRIBE_REQUESTED,
            AgentBusEventType.A2A_CALL_REQUESTED, AgentBusEventType.A2A_CALL_QUERY_REQUESTED,
            AgentBusEventType.A2A_STREAM_SUBSCRIBE_REQUESTED);

    private final BrokerForwardingConsumerPort consumer;
    private final String consumerServiceId;
    private final String tenantId;
    private final ConcurrentHashMap<String, BrokerInboundMessage> inFlight = new ConcurrentHashMap<>();

    /**
     * Creates a new instance.
     *
     * @param consumer
     *            the consumer value
     * @param consumerServiceId
     *            the consumerServiceId value
     * @param tenantId
     *            the tenantId value
     * @param targetServiceId
     *            the targetServiceId value
     */
    public AgentBusBrokerDeliveryPort(BrokerForwardingConsumerPort consumer, String consumerServiceId, String tenantId,
            String targetServiceId) {
        this.consumer = consumer;
        this.consumerServiceId = consumerServiceId;
        this.tenantId = tenantId;
        DeliveryFilter filter = DeliveryFilter.forRuntime(tenantId, targetServiceId);
        REQUEST_TYPES.forEach(type -> consumer.subscribe(consumerServiceId, type, filter));
    }

    /**
     * Polls the next delivery for this runtime subscription.
     *
     * @param requestedConsumerServiceId
     *            requested consumer identity
     * @param requestedTenantId
     *            requested tenant identity
     * @param nowEpochMillis
     *            current epoch time in milliseconds
     *
     * @return next delivery, if available
     */
    public Optional<Delivery> poll(String requestedConsumerServiceId, String requestedTenantId, long nowEpochMillis) {
        if (!consumerServiceId.equals(requestedConsumerServiceId) || !tenantId.equals(requestedTenantId)) {
            throw new IllegalArgumentException("consumer identity or tenant does not match subscription");
        }
        return consumer.poll(nowEpochMillis).map(this::adapt);
    }

    private Delivery adapt(BrokerInboundMessage message) {
        AgentBusEventType eventType = message.eventType();
        if (!REQUEST_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("agent-bus request event type is missing or unsupported");
        }
        String sourceServiceId = message.originalCaller() == null
                ? message.sourceServiceId()
                : message.originalCaller();
        byte[] inlinePayload = message.inlinePayload() == null
                ? null
                : message.inlinePayload().getBytes(StandardCharsets.UTF_8);
        Map<String, String> metadata = message.capability() == null
                ? Map.of()
                : Map.of("capability", message.capability());
        AgentBusEventEnvelope envelope = new AgentBusEventEnvelope("1.0", eventType.name(), message.messageId(),
                message.tenantId(), sourceServiceId, message.targetServiceId(), message.routeHandle(),
                message.correlationId(), message.traceId(), message.idempotencyKey(),
                Instant.ofEpochMilli(message.deadlineMillisEpoch()), "application/json", inlinePayload,
                message.payloadRef(), metadata);
        inFlight.put(message.messageId(), message);
        return new Delivery(envelope, inlinePayload);
    }

    /**
     * Acknowledges a successfully handled delivery.
     *
     * @param delivery
     *            delivery to acknowledge
     */
    public void commit(Delivery delivery) {
        consumer.commit(remove(delivery));
    }

    /**
     * Rejects a delivery with a stable reason.
     *
     * @param delivery
     *            delivery to reject
     * @param reason
     *            rejection reason
     */
    public void reject(Delivery delivery, RejectReason reason) {
        ForwardingFailureCode code = switch (reason) {
            case INVALID_ENVELOPE -> ForwardingFailureCode.TENANT_MISMATCH;
            case INVALID_PAYLOAD -> ForwardingFailureCode.PAYLOAD_REF_INVALID;
            case PROCESSING_FAILED -> ForwardingFailureCode.RECEIVER_UNAVAILABLE;
        };
        consumer.reject(remove(delivery), code);
    }

    private BrokerInboundMessage remove(Delivery delivery) {
        BrokerInboundMessage message = inFlight.remove(delivery.envelope().messageId());
        if (message == null) {
            throw new IllegalStateException("delivery is not in-flight");
        }
        return message;
    }

    /** {@inheritDoc} */
    @Override
    public void close() {
        consumer.close();
    }

    /** Carries a normalized runtime event and its broker payload. */
    public record Delivery(AgentBusEventEnvelope envelope, byte[] payload) {
    }

    /** Lists runtime-neutral rejection reasons. */
    public enum RejectReason {
        INVALID_ENVELOPE, INVALID_PAYLOAD, PROCESSING_FAILED
    }
}
