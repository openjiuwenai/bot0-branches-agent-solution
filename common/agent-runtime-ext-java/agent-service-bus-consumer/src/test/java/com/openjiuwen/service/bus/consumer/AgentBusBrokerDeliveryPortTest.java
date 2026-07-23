/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;
import com.openjiuwen.service.bus.consumer.port.BrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusBrokerDeliveryPort;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Tests AgentBusBrokerDeliveryPort behavior.
 *
 * @since 2026-07-22
 */
class AgentBusBrokerDeliveryPortTest {
    @Test
    void subscribesAndMapsCanonicalAgentBusDelivery() {
        FakeConsumer consumer = new FakeConsumer();
        consumer.next = new BrokerInboundMessage("tenant-a", "m-1", "gateway-a", "runtime-a", "runtime-runtime-a",
                null, "corr-1", AgentBusEventType.CLIENT_INVOCATION_REQUESTED, "trace-1", "idem-1", "route-1", "chat",
                1_800_000_000_000L, "{\"method\":\"SendMessage\"}", "gateway-a");
        AgentBusBrokerDeliveryPort adapter = new AgentBusBrokerDeliveryPort(consumer, "runtime-runtime-a", "tenant-a",
                "runtime-a");

        BrokerDeliveryPort.Delivery delivery = adapter.poll("runtime-runtime-a", "tenant-a", 1L).orElseThrow();
        assertThat(consumer.subscriptions).hasSize(8);
        assertThat(delivery.envelope().eventType()).isEqualTo("CLIENT_INVOCATION_REQUESTED");
        assertThat(delivery.envelope().traceId()).isEqualTo("trace-1");
        assertThat(delivery.envelope().routeHandle()).isEqualTo("route-1");
        assertThat(delivery.envelope().sourceServiceId()).isEqualTo("gateway-a");
        assertThat(delivery.envelope().inlinePayload()).isEqualTo(delivery.payload());
        adapter.commit(delivery);
        assertThat(consumer.committed).isEqualTo("m-1");
    }

    private static final class FakeConsumer implements BrokerForwardingConsumerPort {
        final List<AgentBusEventType> subscriptions = new ArrayList<>();
        BrokerInboundMessage next;
        String committed;

        /** {@inheritDoc} */
        @Override
        public void subscribe(String service, AgentBusEventType type, DeliveryFilter filter) {
            subscriptions.add(type);
        }

        /** {@inheritDoc} */
        @Override
        public Optional<BrokerInboundMessage> poll(long now) {
            BrokerInboundMessage value = next;
            next = null;
            return Optional.ofNullable(value);
        }

        /** {@inheritDoc} */
        @Override
        public void commit(BrokerInboundMessage message) {
            committed = message.messageId();
        }

        /** {@inheritDoc} */
        @Override
        public void reject(BrokerInboundMessage message, ForwardingFailureCode code) {
        }

        /** {@inheritDoc} */
        @Override
        public void close() {
        }

        /** {@inheritDoc} */
        @Override
        public boolean supportsBrokerSidePropertyFilter() {
            return true;
        }
    }
}
