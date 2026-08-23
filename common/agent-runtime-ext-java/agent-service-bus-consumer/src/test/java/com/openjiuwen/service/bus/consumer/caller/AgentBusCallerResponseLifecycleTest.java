/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.AgentBusRequestSubmitter;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests that the caller response poll chain survives a malformed message.
 *
 * @since 2026-08-23
 */
class AgentBusCallerResponseLifecycleTest {
    @Test
    void survivesAMessageWithoutCorrelationId() throws InterruptedException {
        BrokerInboundMessage malformed = new BrokerInboundMessage("tenant-a", "message-1", "runtime-b", "runtime-a",
                "runtime-a", null, null, AgentBusEventType.A2A_CALL_TERMINAL);
        RecordingConsumerPort consumer = new RecordingConsumerPort(malformed);
        var lifecycle = new AgentBusCallerResponseLifecycle(consumer, caller(),
                Executors.newSingleThreadExecutor(), "runtime-a", "tenant-a", "runtime-a");

        lifecycle.start();
        try {
            // The malformed message fails validate() and is discarded through failProtocol(null, ...);
            // the chain must keep polling instead of dying with the thread that handled it.
            assertThat(consumer.threePolls.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(consumer.committed).containsExactly("message-1");
            assertThat(consumer.rejected).isEmpty();
        } finally {
            lifecycle.stop();
        }
    }

    private static AgentBusRemoteAgentCaller caller() {
        AgentBusRequestSubmitter submitter = envelope -> ForwardingReceipt.accepted(envelope.messageId(),
                envelope.tenantId(), System.currentTimeMillis());
        return new AgentBusRemoteAgentCaller(new RuntimeRdcClient(URI.create("http://127.0.0.1:1")),
                submitter, Runnable::run, "tenant-a", "runtime-a", 30_000L);
    }

    private static final class RecordingConsumerPort implements BrokerForwardingConsumerPort {
        private final BrokerInboundMessage first;
        private final AtomicInteger polls = new AtomicInteger();
        private final CountDownLatch threePolls = new CountDownLatch(3);
        private final List<String> committed = new CopyOnWriteArrayList<>();
        private final List<String> rejected = new CopyOnWriteArrayList<>();

        private RecordingConsumerPort(BrokerInboundMessage first) {
            this.first = first;
        }

        @Override
        public void subscribe(String consumerServiceId, AgentBusEventType eventType, DeliveryFilter filter) {
        }

        @Override
        public Optional<BrokerInboundMessage> poll(long nowMillis) {
            threePolls.countDown();
            return polls.incrementAndGet() == 1 ? Optional.of(first) : Optional.empty();
        }

        @Override
        public void commit(BrokerInboundMessage message) {
            committed.add(message.messageId());
        }

        @Override
        public void reject(BrokerInboundMessage message, ForwardingFailureCode failureCode) {
            rejected.add(message.messageId());
        }

        @Override
        public void close() {
        }

        @Override
        public boolean supportsBrokerSidePropertyFilter() {
            return false;
        }
    }
}
