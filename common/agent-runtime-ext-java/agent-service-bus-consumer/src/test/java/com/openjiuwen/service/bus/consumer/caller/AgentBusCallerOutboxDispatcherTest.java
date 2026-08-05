/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.bus.forwarding.test.InMemoryForwardingOutbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Tests the Runtime caller outbox pump against the Agent Bus SPI.
 *
 * @since 2026-08-04
 */
class AgentBusCallerOutboxDispatcherTest {
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void publishesAndAcknowledgesOwnedRequest() {
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        ForwardingEnvelope envelope = envelope();
        outbox.enqueue(envelope, "runtime-a", "runtime-b", 100L);
        BrokerForwardingProducerPort producer = (record, now) -> BrokerProduceOutcome.accepted();
        var dispatcher = new AgentBusCallerOutboxDispatcher(outbox, outbox, producer, scheduler,
                "tenant-a", "runtime-a", 1_000L, 100L, 10);

        assertThat(dispatcher.dispatchOnce(200L)).isOne();
        assertThat(outbox.statusOf(envelope.messageId(), "tenant-a")).isEqualTo(ForwardingStatus.Outbox.ACKED);
    }

    private static ForwardingEnvelope envelope() {
        return new ForwardingEnvelope(new ForwardingMessageId("message-1"), AgentBusEventType.A2A_CALL_REQUESTED,
                "tenant-a", "trace-1", "correlation-1", "idempotency-1",
                new ForwardingRouteHandle("route-b", "tenant-a"), "a2a-call", "runtime-a", "runtime-b",
                10_000L, ForwardingEnvelope.PayloadPolicy.DATA_BEARING, null, "{\"jsonrpc\":\"2.0\"}",
                "runtime-a");
    }
}
