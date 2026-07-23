/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusOutboxResponsePublisher;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests conversion of runtime projections to agent-bus outbox envelopes.
 *
 * @since 2026-07-22
 */
class AgentBusOutboxResponsePublisherTest {
    @Test
    void reversesRequestDirectionWhenPublishingResponse() {
        AtomicReference<ForwardingEnvelope> captured = new AtomicReference<>();
        Object candidate = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ForwardingOutboxPort.class}, (proxy, method, args) -> {
                    if ("enqueue".equals(method.getName()) && args[0] instanceof ForwardingEnvelope envelope) {
                        captured.set(envelope);
                    }
                    return null;
                });
        if (!(candidate instanceof ForwardingOutboxPort outbox)) {
            throw new AssertionError("ForwardingOutboxPort proxy has an incompatible type");
        }
        var publisher = new AgentBusOutboxResponsePublisher(outbox, "runtime-a");
        publisher.publish(new BusResponseProjection("event", "INVOCATION_ACCEPTED", "tenant-a", "corr", "task",
                Instant.now(), Map.of(), "trace", "runtime-a", "gateway-a", "route", "idem", "request-message",
                "ACCEPTED", 0));

        assertThat(captured.get().sourceServiceId()).isEqualTo("runtime-a");
        assertThat(captured.get().targetServiceId()).isEqualTo("gateway-a");
        assertThat(captured.get().payloadPolicy()).isEqualTo(ForwardingEnvelope.PayloadPolicy.DATA_BEARING);
        assertThat(captured.get().payloadRef()).isNull();
        assertThat(captured.get().inlinePayload()).contains("taskId=task");
    }

    @Test
    void publishesInputRequiredThroughTheCanonicalAgentBusEventType() {
        AtomicReference<ForwardingEnvelope> captured = new AtomicReference<>();
        Object candidate = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{ForwardingOutboxPort.class}, (proxy, method, args) -> {
                    if ("enqueue".equals(method.getName()) && args[0] instanceof ForwardingEnvelope envelope) {
                        captured.set(envelope);
                    }
                    return null;
                });
        if (!(candidate instanceof ForwardingOutboxPort outbox)) {
            throw new AssertionError("ForwardingOutboxPort proxy has an incompatible type");
        }
        var publisher = new AgentBusOutboxResponsePublisher(outbox, "runtime-a");
        publisher.publish(new BusResponseProjection("input-event", "INVOCATION_INPUT_REQUIRED", "tenant-a", "corr",
                "task", Instant.now(), Map.of("taskState", "TASK_STATE_INPUT_REQUIRED"), "trace", "runtime-a",
                "gateway-a", "route", "idem", "request-message", "INPUT_REQUIRED", 1));

        assertThat(captured.get().eventType().name()).isEqualTo("INVOCATION_INPUT_REQUIRED");
        assertThat(captured.get().inlinePayload()).contains("taskId=task", "status=input_required");
    }
}
