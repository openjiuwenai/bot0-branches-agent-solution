/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import com.openjiuwen.service.bus.consumer.model.BusConsumptionDecision;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One delivery tick: process first, then commit only on a consumed disposition.
 *
 * @since 2026-07-22
 */
public final class BrokerDeliveryLoop {
    private final Supplier<Optional<AgentBusBrokerDeliveryPort.Delivery>> poller;
    private final Consumer<AgentBusBrokerDeliveryPort.Delivery> committer;
    private final BiConsumer<AgentBusBrokerDeliveryPort.Delivery, AgentBusBrokerDeliveryPort.RejectReason> rejecter;
    private final Function<AgentBusBrokerDeliveryPort.Delivery, BusConsumptionDecision> consumer;

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
    public BrokerDeliveryLoop(AgentBusBrokerDeliveryPort broker,
            Function<AgentBusBrokerDeliveryPort.Delivery, BusConsumptionDecision> consumer,
            String consumerServiceId, String tenantId, Clock clock) {
        Objects.requireNonNull(broker);
        Objects.requireNonNull(consumerServiceId);
        Objects.requireNonNull(tenantId);
        Objects.requireNonNull(clock);
        this.poller = () -> broker.poll(consumerServiceId, tenantId, clock.millis());
        this.committer = broker::commit;
        this.rejecter = broker::reject;
        this.consumer = Objects.requireNonNull(consumer);
    }

    /**
     * Creates a delivery loop from operation functions. Intended for isolated SDK tests.
     *
     * @param poller
     *            delivery poll operation
     * @param committer
     *            delivery commit operation
     * @param rejecter
     *            delivery reject operation
     * @param consumer
     *            runtime event consumer
     */
    public BrokerDeliveryLoop(Supplier<Optional<AgentBusBrokerDeliveryPort.Delivery>> poller,
            Consumer<AgentBusBrokerDeliveryPort.Delivery> committer,
            BiConsumer<AgentBusBrokerDeliveryPort.Delivery, AgentBusBrokerDeliveryPort.RejectReason> rejecter,
            Function<AgentBusBrokerDeliveryPort.Delivery, BusConsumptionDecision> consumer) {
        this.poller = Objects.requireNonNull(poller);
        this.committer = Objects.requireNonNull(committer);
        this.rejecter = Objects.requireNonNull(rejecter);
        this.consumer = Objects.requireNonNull(consumer);
    }

    /**
     * Performs the tick operation.
     *
     * @return the operation result
     */
    public boolean tick() {
        var delivery = poller.get();
        if (delivery.isEmpty()) {
            return false;
        }
        var d = delivery.get();
        BusConsumptionDecision result = consumer.apply(d);
        switch (result.type()) {
            case ACK_CONSUMED, ACK_REJECTED -> committer.accept(d);
            case RETRY -> rejecter.accept(d, AgentBusBrokerDeliveryPort.RejectReason.PROCESSING_FAILED);
        }
        return true;
    }
}
