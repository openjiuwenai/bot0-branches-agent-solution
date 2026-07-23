/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.testkit;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.port.BrokerDeliveryPort;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * In-memory fixture for the runtime delivery port.
 *
 * @since 2026-07-22
 */
public final class InMemoryBrokerDeliveryPort implements BrokerDeliveryPort {
    private final ConcurrentLinkedQueue<Delivery> available = new ConcurrentLinkedQueue<>();
    private final Map<String, Delivery> inFlight = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Delivery> committed = new ConcurrentLinkedQueue<>();
    private final Map<String, RejectReason> rejected = new ConcurrentHashMap<>();

    /**
     * Adds a delivery to the in-memory receiver.
     *
     * @param envelope
     *            event envelope
     * @param payload
     *            broker payload
     */
    public void enqueue(AgentBusEventEnvelope envelope, byte[] payload) {
        available.add(new Delivery(Objects.requireNonNull(envelope), payload));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Delivery> poll(String consumerServiceId, String tenantId, long nowEpochMillis) {
        Delivery delivery = available.poll();
        if (delivery == null) {
            return Optional.empty();
        }
        if (!Objects.equals(tenantId, delivery.envelope().tenantId())) {
            available.add(delivery);
            throw new IllegalArgumentException("tenant does not match delivery");
        }
        inFlight.put(delivery.envelope().messageId(), delivery);
        return Optional.of(delivery);
    }

    /** {@inheritDoc} */
    @Override
    public void commit(Delivery delivery) {
        committed.add(remove(delivery));
    }

    /** {@inheritDoc} */
    @Override
    public void reject(Delivery delivery, RejectReason reason) {
        Delivery removed = remove(delivery);
        rejected.put(removed.envelope().messageId(), Objects.requireNonNull(reason));
    }

    /**
     * Returns whether the delivery was committed.
     *
     * @param messageId
     *            message identity
     *
     * @return {@code true} when committed
     */
    public boolean isCommitted(String messageId) {
        return committed.stream().anyMatch(delivery -> Objects.equals(messageId, delivery.envelope().messageId()));
    }

    /**
     * Returns the recorded rejection reason.
     *
     * @param messageId
     *            message identity
     *
     * @return rejection reason, if rejected
     */
    public Optional<RejectReason> rejectionReason(String messageId) {
        return Optional.ofNullable(rejected.get(messageId));
    }

    private Delivery remove(Delivery delivery) {
        Objects.requireNonNull(delivery);
        Delivery removed = inFlight.remove(delivery.envelope().messageId());
        if (removed != delivery) {
            if (removed != null) {
                inFlight.put(removed.envelope().messageId(), removed);
            }
            throw new IllegalStateException("delivery is not in-flight");
        }
        return removed;
    }
}
