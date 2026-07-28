/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

/**
 * Test double for {@link BrokerForwardingConsumerPort}: records subscribes; FIFO poll; records
 * commits/rejects. Mirrors the existing Fake*Port convention in {@code bus.control}.
 *
 * @since 2026-07-27
 */
public class FakeBrokerForwardingConsumerPort implements BrokerForwardingConsumerPort {
    /** One recorded subscribe call. */
    public record Subscription(String consumerServiceId, AgentBusEventType eventType, DeliveryFilter filter) {
    }

    private final List<Subscription> subscriptions = new ArrayList<>();
    private final Queue<BrokerInboundMessage> inbound = new LinkedList<>();
    private final List<BrokerInboundMessage> committed = new ArrayList<>();
    private final List<BrokerInboundMessage> rejected = new ArrayList<>();
    private boolean closed = false;

    /**
     * Returns recorded subscribe calls in order.
     *
     * @return recorded subscribes
     */
    public List<Subscription> subscriptions() {
        return subscriptions;
    }

    /**
     * Returns messages committed (matched or skipped), in order.
     *
     * @return committed messages
     */
    public List<BrokerInboundMessage> committed() {
        return committed;
    }

    /**
     * Returns messages rejected, in order.
     *
     * @return rejected messages
     */
    public List<BrokerInboundMessage> rejected() {
        return rejected;
    }

    /**
     * Whether {@link #close} was called.
     *
     * @return whether close was called
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Enqueues an inbound message for the next {@link #poll}.
     *
     * @param message message to return on next poll
     */
    public void enqueue(BrokerInboundMessage message) {
        inbound.add(message);
    }

    @Override
    public void subscribe(String consumerServiceId, AgentBusEventType eventType, DeliveryFilter filter) {
        subscriptions.add(new Subscription(consumerServiceId, eventType, filter));
    }

    @Override
    public Optional<BrokerInboundMessage> poll(long nowMillisEpoch) {
        return Optional.ofNullable(inbound.poll());
    }

    @Override
    public void commit(BrokerInboundMessage message) {
        committed.add(message);
    }

    @Override
    public void reject(BrokerInboundMessage message, ForwardingFailureCode code) {
        rejected.add(message);
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public boolean supportsBrokerSidePropertyFilter() {
        return true;
    }
}
