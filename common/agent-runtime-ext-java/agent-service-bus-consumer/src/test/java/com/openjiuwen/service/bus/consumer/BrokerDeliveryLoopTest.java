/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusConsumptionDecision;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusBrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.BrokerDeliveryLoop;
import com.openjiuwen.service.bus.consumer.testkit.InMemoryBrokerDeliveryPort;

import org.junit.jupiter.api.Test;

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
        var event = new AgentBusEventEnvelope("A2A_CALL_REQUESTED", "m", "t", "s", "r", null, "c", "x", "i",
                java.time.Instant.now().plusSeconds(10), new byte[]{1}, null, Map.of());
        var seen = new ArrayList<String>();
        var delivery = new AgentBusBrokerDeliveryPort.Delivery(event, new byte[]{1});
        var loop = new BrokerDeliveryLoop(() -> Optional.of(delivery), ignored -> seen.add("commit"),
                (ignored, reason) -> seen.add("reject"), ignored -> BusConsumptionDecision.consumed());
        assertThat(loop.tick()).isTrue();
        assertThat(seen).containsExactly("commit");
    }

    @Test
    void inMemoryPortPollsAndCommitsConsumedDelivery() {
        var port = new InMemoryBrokerDeliveryPort();
        var event = event("committed-message");
        port.enqueue(event, null);
        var loop = new BrokerDeliveryLoop(() -> port.poll("runtime", "t", System.currentTimeMillis()), port::commit,
                port::reject, delivery -> BusConsumptionDecision.consumed());

        assertThat(loop.tick()).isTrue();
        assertThat(port.isCommitted("committed-message")).isTrue();
        assertThat(loop.tick()).isFalse();
    }

    @Test
    void inMemoryPortRecordsRejectedRetryDelivery() {
        var port = new InMemoryBrokerDeliveryPort();
        var event = event("rejected-message");
        port.enqueue(event, null);
        var loop = new BrokerDeliveryLoop(() -> port.poll("runtime", "t", System.currentTimeMillis()), port::commit,
                port::reject, delivery -> BusConsumptionDecision.retry("temporary"));

        assertThat(loop.tick()).isTrue();
        assertThat(port.rejectionReason("rejected-message"))
                .contains(AgentBusBrokerDeliveryPort.RejectReason.PROCESSING_FAILED);
    }

    private static AgentBusEventEnvelope event(String messageId) {
        return new AgentBusEventEnvelope("CLIENT_INVOCATION_QUERY_REQUESTED", messageId, "t", "s", "r", null,
                "c", "trace", "i", java.time.Instant.now().plusSeconds(10), null, "ref://x", Map.of());
    }
}
