/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerOutboundMessage;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusResponsePublisher;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests direct publication of runtime projections through the agent-bus runtime role.
 *
 * @since 2026-07-22
 */
class AgentBusResponsePublisherTest {
    @Test
    void reversesRequestDirectionWhenPublishingResponse() {
        AtomicReference<BrokerOutboundMessage> captured = new AtomicReference<>();
        var publisher = new AgentBusResponsePublisher(recordingProducer(captured), "runtime-a");
        publisher.publish(new BusResponseProjection("event", "INVOCATION_ACCEPTED", "tenant-a", "corr", "task",
                Instant.now(), Map.of(), "trace", "runtime-a", "gateway-a", "route", "idem", "request-message",
                "ACCEPTED", 0));

        assertThat(captured.get().routingDescriptor()).isEqualTo("target=gateway-a");
        assertThat(captured.get().headers().sourceServiceId()).isEqualTo("runtime-a");
        assertThat(captured.get().headers().targetServiceId()).isEqualTo("gateway-a");
        assertThat(captured.get().headers().payloadRef()).isNull();
        assertThat(captured.get().headers().inlinePayload()).contains("taskId=task");
    }

    @Test
    void publishesInputRequiredThroughTheCanonicalAgentBusEventType() {
        AtomicReference<BrokerOutboundMessage> captured = new AtomicReference<>();
        var publisher = new AgentBusResponsePublisher(recordingProducer(captured), "runtime-a");
        publisher.publish(new BusResponseProjection("input-event", "INVOCATION_INPUT_REQUIRED", "tenant-a", "corr",
                "task", Instant.now(), Map.of("taskState", "TASK_STATE_INPUT_REQUIRED"), "trace", "runtime-a",
                "gateway-a", "route", "idem", "request-message", "INPUT_REQUIRED", 1));

        assertThat(captured.get().headers().eventType().name()).isEqualTo("INVOCATION_INPUT_REQUIRED");
        assertThat(captured.get().headers().inlinePayload()).contains("taskId=task", "status=input_required");
    }

    @Test
    void surfacesBrokerFailureForProjectionRetry() {
        BrokerForwardingProducerPort unavailable = new BrokerForwardingProducerPort() {
            @Override
            public BrokerProduceOutcome produce(ForwardingOutboxRecord record, long nowMillisEpoch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BrokerProduceOutcome produce(BrokerOutboundMessage message, long nowMillisEpoch) {
                return BrokerProduceOutcome.unavailable(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
            }
        };
        var publisher = new AgentBusResponsePublisher(unavailable, "runtime-a");
        BusResponseProjection projection = new BusResponseProjection("event", "INVOCATION_RESPONSE", "tenant-a",
                "corr", "task", Instant.now(), Map.of(), "trace", "runtime-a", "gateway-a", "route", "idem",
                "request-message", "RESPONSE", 1);

        assertThatThrownBy(() -> publisher.publish(projection))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("UNAVAILABLE");
    }

    private static BrokerForwardingProducerPort recordingProducer(AtomicReference<BrokerOutboundMessage> captured) {
        return new BrokerForwardingProducerPort() {
            @Override
            public BrokerProduceOutcome produce(ForwardingOutboxRecord record, long nowMillisEpoch) {
                throw new UnsupportedOperationException();
            }

            @Override
            public BrokerProduceOutcome produce(BrokerOutboundMessage message, long nowMillisEpoch) {
                captured.set(message);
                return BrokerProduceOutcome.accepted();
            }
        };
    }
}
