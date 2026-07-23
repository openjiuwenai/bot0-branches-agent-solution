/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusConsumptionDecision;
import com.openjiuwen.service.bus.consumer.port.BrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.BrokerDeliveryLoop;
import com.openjiuwen.service.bus.consumer.testkit.InMemoryBrokerDeliveryPort;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

/**
 * Tests BrokerDeliveryLoop behavior.
 *
 * @since 2026-07-22
 */
class BrokerDeliveryLoopTest {
    @Test
    void commitsConsumedAndRejectsRetry() {
        var event = new AgentBusEventEnvelope("1.0", "A2A_CALL_REQUESTED", "m", "t", "s", "r", null, "c", "x", "i",
                java.time.Instant.now().plusSeconds(10), "application/json", new byte[]{1}, null, Map.of());
        var seen = new ArrayList<String>();
        BrokerDeliveryPort port = new BrokerDeliveryPort() {
            /** {@inheritDoc} */
            @Override
            public Optional<Delivery> poll(String c, String t, long n) {
                return Optional.of(new Delivery(event, new byte[]{1}));
            }

            /** {@inheritDoc} */
            @Override
            public void commit(Delivery d) {
                seen.add("commit");
            }

            /** {@inheritDoc} */
            @Override
            public void reject(Delivery d, RejectReason r) {
                seen.add("reject");
            }
        };
        var loop = new BrokerDeliveryLoop(port, d -> BusConsumptionDecision.consumed(), "svc", "t", Clock.systemUTC());
        assertThat(loop.tick()).isTrue();
        assertThat(seen).containsExactly("commit");
    }

    @Test
    void inMemoryPortPollsAndCommitsConsumedDelivery() {
        var port = new InMemoryBrokerDeliveryPort();
        var event = event("committed-message");
        port.enqueue(event, null);
        var loop = new BrokerDeliveryLoop(port, delivery -> BusConsumptionDecision.consumed(), "runtime", "t",
                Clock.systemUTC());

        assertThat(loop.tick()).isTrue();
        assertThat(port.isCommitted("committed-message")).isTrue();
        assertThat(loop.tick()).isFalse();
    }

    @Test
    void inMemoryPortRecordsRejectedRetryDelivery() {
        var port = new InMemoryBrokerDeliveryPort();
        var event = event("rejected-message");
        port.enqueue(event, null);
        var loop = new BrokerDeliveryLoop(port, delivery -> BusConsumptionDecision.retry("temporary"), "runtime", "t",
                Clock.systemUTC());

        assertThat(loop.tick()).isTrue();
        assertThat(port.rejectionReason("rejected-message"))
                .contains(BrokerDeliveryPort.RejectReason.PROCESSING_FAILED);
    }

    private static AgentBusEventEnvelope event(String messageId) {
        return new AgentBusEventEnvelope("1.0", "CLIENT_INVOCATION_QUERY_REQUESTED", messageId, "t", "s", "r", null,
                "c", "trace", "i", java.time.Instant.now().plusSeconds(10), "application/json", null, "ref://x",
                Map.of());
    }
}
