/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import com.openjiuwen.service.bus.consumer.model.BusConsumptionDecision;
import com.openjiuwen.service.bus.consumer.port.BrokerDeliveryPort;

import java.time.Clock;
import java.util.Objects;

/**
 * One delivery tick: process first, then commit only on a consumed disposition.
 *
 * @since 2026-07-22
 */
public final class BrokerDeliveryLoop {
    private final BrokerDeliveryPort broker;
    private final RuntimeBusEventConsumerAdapter consumer;
    private final String consumerServiceId;
    private final String tenantId;
    private final Clock clock;

    /**
     * Creates a new instance.
     *
     * @param broker
     *            the broker value
     * @param consumer
     *            the consumer value
     * @param consumerServiceId
     *            the consumerServiceId value
     * @param tenantId
     *            the tenantId value
     * @param clock
     *            the clock value
     */
    public BrokerDeliveryLoop(BrokerDeliveryPort broker, RuntimeBusEventConsumerAdapter consumer,
            String consumerServiceId, String tenantId, Clock clock) {
        this.broker = Objects.requireNonNull(broker);
        this.consumer = Objects.requireNonNull(consumer);
        this.consumerServiceId = Objects.requireNonNull(consumerServiceId);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.clock = clock;
    }

    /**
     * Performs the tick operation.
     *
     * @return the operation result
     */
    public boolean tick() {
        var delivery = broker.poll(consumerServiceId, tenantId, clock.millis());
        if (delivery.isEmpty()) {
            return false;
        }
        var d = delivery.get();
        BusConsumptionDecision result = consumer.consume(d);
        switch (result.type()) {
            case ACK_CONSUMED, ACK_REJECTED -> broker.commit(d);
            case RETRY -> broker.reject(d, BrokerDeliveryPort.RejectReason.PROCESSING_FAILED);
        }
        return true;
    }

    /** Converts a broker delivery into the runtime consumption decision. */
    @FunctionalInterface
    public interface RuntimeBusEventConsumerAdapter {
        /**
         * Consumes one broker delivery.
         *
         * @param delivery
         *            broker delivery to consume
         *
         * @return runtime consumption decision
         */
        BusConsumptionDecision consume(BrokerDeliveryPort.Delivery delivery);
    }
}
