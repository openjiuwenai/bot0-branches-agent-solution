/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusConsumptionDecision;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.validation.BusEnvelopeValidator;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Tests RuntimeBusEventConsumer behavior.
 *
 * @since 2026-07-22
 */
class RuntimeBusEventConsumerTest {
    @Test
    void consumesOnceAndDeduplicatesCreation() {
        AtomicInteger calls = new AtomicInteger();
        RequestHandlerBusA2aBridge bridge = bridge((e, payload) -> {
            calls.incrementAndGet();
            return new BusDispatchResult("task-1", null, null, false);
        });
        RuntimeBusEventConsumer consumer = consumer(bridge, e -> e.inlinePayload());
        AgentBusEventEnvelope event = event("CLIENT_INVOCATION_REQUESTED", "idem-1");
        assertThat(consumer.consume(event, null).type()).isEqualTo(BusConsumptionDecision.Type.ACK_CONSUMED);
        assertThat(consumer.consume(event, null).type()).isEqualTo(BusConsumptionDecision.Type.ACK_CONSUMED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void retriesTransientBridgeFailure() {
        RuntimeBusEventConsumer consumer = consumer(bridge((e, p) -> {
            throw new IllegalStateException("down");
        }), e -> e.inlinePayload());
        assertThat(consumer.consume(event("A2A_CALL_REQUESTED", "idem-2"), null).type())
                .isEqualTo(BusConsumptionDecision.Type.RETRY);
    }

    @Test
    void passesReservedTaskIdToTaskAwareBridge() {
        AtomicInteger calls = new AtomicInteger();
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge() {
            @Override
            public boolean supportsReservedTaskId() {
                return true;
            }

            @Override
            public BusDispatchResult handle(AgentBusEventEnvelope event, byte[] payload, String reservedTaskId) {
                calls.incrementAndGet();
                assertThat(reservedTaskId).startsWith("bus-");
                return new BusDispatchResult(reservedTaskId, null, null, false);
            }
        };
        RuntimeBusEventConsumer consumer = consumer(bridge, e -> e.inlinePayload());
        assertThat(consumer.consume(event("CLIENT_INVOCATION_REQUESTED", "idem-aware"), null).type())
                .isEqualTo(BusConsumptionDecision.Type.ACK_CONSUMED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void continuationUsesExistingTaskIdWithoutTaskIdAwareExtension() {
        AtomicInteger calls = new AtomicInteger();
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge() {
            @Override
            public BusDispatchResult handle(AgentBusEventEnvelope event, byte[] payload) {
                calls.incrementAndGet();
                return new BusDispatchResult("existing-task", null, null, false);
            }

            @Override
            public Optional<String> requestedTaskId(AgentBusEventEnvelope event, byte[] payload) {
                return Optional.of("existing-task");
            }
        };
        assertThat(consumer(bridge, e -> e.inlinePayload())
                .consume(event("CLIENT_INVOCATION_REQUESTED", "continuation"), null).type())
                .isEqualTo(BusConsumptionDecision.Type.ACK_CONSUMED);
        assertThat(calls).hasValue(1);
    }

    @Test
    void controlTaskNotFoundPublishesDeterministicFailedProjection() {
        var published = new ArrayList<BusResponseProjection>();
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        RuntimeBusEventConsumer consumer = new RuntimeBusEventConsumer(
                new BusEnvelopeValidator(Clock.fixed(now, ZoneOffset.UTC), "tenant-a", "runtime-a"),
                e -> e.inlinePayload(),
                new InMemoryBusTaskAdmissionStore(),
                bridge((e, p) -> new BusDispatchResult(null, null, null, false)),
                new BusTaskProjectionCoordinator(
                        new com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore(),
                        published::add));
        var decision = consumer.consume(event("CLIENT_INVOCATION_QUERY_REQUESTED", "query-missing"), null);
        assertThat(decision.type()).isEqualTo(BusConsumptionDecision.Type.ACK_REJECTED);
        assertThat(published).singleElement().satisfies(projection -> {
            assertThat(projection.eventType()).isEqualTo("INVOCATION_FAILED");
            assertThat(projection.data()).containsEntry("errorCode", "TASK_NOT_FOUND").containsEntry("retryable",
                    false);
        });
    }

    private RuntimeBusEventConsumer consumer(RequestHandlerBusA2aBridge bridge,
            Function<AgentBusEventEnvelope, byte[]> resolver) {
        Instant now = Instant.parse("2026-07-20T00:00:00Z");
        return new RuntimeBusEventConsumer(
                new BusEnvelopeValidator(Clock.fixed(now, ZoneOffset.UTC), "tenant-a", "runtime-a"), resolver,
                new InMemoryBusTaskAdmissionStore(), bridge, new BusTaskProjectionCoordinator(
                        new com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore(), p -> {
                        }));
    }

    private static RequestHandlerBusA2aBridge bridge(
            BiFunction<AgentBusEventEnvelope, byte[], BusDispatchResult> dispatch) {
        return new RequestHandlerBusA2aBridge() {
            @Override
            public BusDispatchResult handle(AgentBusEventEnvelope event, byte[] payload) {
                return dispatch.apply(event, payload);
            }
        };
    }

    private AgentBusEventEnvelope event(String type, String key) {
        byte[] payload = ("{\"jsonrpc\":\"2.0\",\"id\":\"" + key
                + "\",\"method\":\"SendMessage\",\"params\":{}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new AgentBusEventEnvelope(type, key, "tenant-a", "source", "runtime-a", null, "corr-1", "trace-1",
                key, Instant.parse("2026-07-20T00:01:00Z"), payload, null, Map.of());
    }
}
