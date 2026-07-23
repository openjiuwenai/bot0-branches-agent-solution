/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.integration;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;
import com.openjiuwen.service.bus.consumer.BusTaskProjectionCoordinator;
import com.openjiuwen.service.bus.consumer.RuntimeBusEventConsumer;
import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;
import com.openjiuwen.service.bus.consumer.port.TaskIdAwareBusA2aRequestBridge;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.validation.BusEnvelopeValidator;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.requesthandlers.DefaultRequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixtures for FEAT-017 agent-bus integration tests.
 *
 * @since 2026-07-22
 */
final class Feat017BusIntegrationSupport {
    static final String TENANT = "tenant-a";
    static final String GATEWAY = "it-gateway-feat017";
    static final String RUNTIME = "it-runtime-feat017";
    static final String RUNTIME_CONSUMER = "runtime-it-runtime-feat017";
    static final String TRACE = "0123456789abcdef0123456789abcdef";
    static final String ROUTE = "route-feat017";
    static final long NOW = 1_800_000_000_000L;
    static final byte[] REQUEST_PAYLOAD = "{\"jsonrpc\":\"2.0\",\"method\":\"SendMessage\"}"
            .getBytes(StandardCharsets.UTF_8);

    private Feat017BusIntegrationSupport() {
    }

    record ServiceEndpoints(String gatewayServiceId, String runtimeServiceId) {
    }

    static ForwardingOutboxRecord requestRecord(String messageId, String correlationId, String idempotencyKey) {
        return requestRecord(AgentBusEventType.CLIENT_INVOCATION_REQUESTED, messageId, correlationId,
                idempotencyKey);
    }

    static ForwardingOutboxRecord requestRecord(AgentBusEventType type, String messageId, String correlationId,
            String idempotencyKey) {
        return requestRecord(type, messageId, correlationId, idempotencyKey, new ServiceEndpoints(GATEWAY, RUNTIME));
    }

    static ForwardingOutboxRecord requestRecord(AgentBusEventType type, String messageId, String correlationId,
            String idempotencyKey, ServiceEndpoints endpoints) {
        return record(new ForwardingEnvelope(new ForwardingMessageId(messageId), type, TENANT, TRACE, correlationId,
                idempotencyKey, new ForwardingRouteHandle(ROUTE, TENANT), "a2a", endpoints.gatewayServiceId(),
                endpoints.runtimeServiceId(),
                NOW + 60_000L, ForwardingEnvelope.PayloadPolicy.DATA_BEARING, "fixture://" + messageId, null,
                endpoints.gatewayServiceId()), NOW);
    }

    static ForwardingOutboxRecord record(ForwardingEnvelope envelope, long now) {
        return new ForwardingOutboxRecord(envelope.tenantId(), envelope.messageId(), envelope.sourceServiceId(),
                envelope.targetServiceId(), envelope.routeHandle(), envelope.payloadRef(),
                ForwardingStatus.Outbox.PENDING, 0, now, now, now, null, null, envelope.correlationId(),
                envelope.eventType(), envelope.traceId(), envelope.idempotencyKey(), envelope.capability(),
                envelope.deadlineMillisEpoch(), envelope.inlinePayload(), envelope.originalCaller());
    }

    static RuntimeBusEventConsumer runtime(RecordingOutbox outbox, AtomicInteger bridgeCalls) {
        return runtime(outbox, bridgeCalls, RUNTIME);
    }

    static RuntimeBusEventConsumer runtime(RecordingOutbox outbox, AtomicInteger bridgeCalls,
            String runtimeServiceId) {
        TaskIdAwareBusA2aRequestBridge bridge = (envelope, payload, reservedTaskId) -> {
            bridgeCalls.incrementAndGet();
            Task task = Task.builder().id(reservedTaskId).contextId("ctx-feat017")
                    .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, null,
                            OffsetDateTime.ofInstant(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)))
                    .build();
            return BusDispatchResult.task(task);
        };
        var publisher = new com.openjiuwen.service.bus.consumer.runtime.AgentBusOutboxResponsePublisher(outbox,
                runtimeServiceId);
        var coordinator = new BusTaskProjectionCoordinator(new InMemoryBusResponseProjectionStore(), publisher);
        return new RuntimeBusEventConsumer(
                new BusEnvelopeValidator(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), runtimeServiceId),
                envelope -> REQUEST_PAYLOAD, new InMemoryBusTaskAdmissionStore(), bridge, coordinator);
    }

    static RuntimeBusEventConsumer queryRuntime(RecordingOutbox outbox, RecordingTaskStore taskStore,
            byte[] queryPayload) {
        return queryRuntime(outbox, taskStore, queryPayload, RUNTIME);
    }

    static RuntimeBusEventConsumer queryRuntime(RecordingOutbox outbox, RecordingTaskStore taskStore,
            byte[] queryPayload, String runtimeServiceId) {
        Executor directExecutor = Runnable::run;
        var requestHandler = DefaultRequestHandler.create(null, taskStore, null, null, null, directExecutor,
                directExecutor);
        var bridge = new RequestHandlerBusA2aBridge(requestHandler);
        var publisher = new com.openjiuwen.service.bus.consumer.runtime.AgentBusOutboxResponsePublisher(outbox,
                runtimeServiceId);
        var coordinator = new BusTaskProjectionCoordinator(new InMemoryBusResponseProjectionStore(), publisher);
        return new RuntimeBusEventConsumer(
                new BusEnvelopeValidator(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC), runtimeServiceId),
                envelope -> queryPayload, new InMemoryBusTaskAdmissionStore(), bridge, coordinator);
    }

    static byte[] queryPayload(String taskId, String clientInvocationId) {
        return """
                {
                  "jsonrpc": "2.0",
                  "method": "GetTask",
                  "params": {
                    "id": "%s",
                    "historyLength": 10,
                    "tenant": "%s"
                  },
                  "clientInvocationId": "%s"
                }
                """.formatted(taskId, TENANT, clientInvocationId).getBytes(StandardCharsets.UTF_8);
    }

    static Task completedTask(String taskId, String contextId) {
        return Task.builder().id(taskId).contextId(contextId)
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED, null,
                        OffsetDateTime.ofInstant(Instant.ofEpochMilli(NOW), ZoneOffset.UTC)))
                .build();
    }

    /**
     * Runtime-owned TaskStore fixture that records the exact identifier used by GetTask.
     */
    static final class RecordingTaskStore implements TaskStore {
        private final TaskStore delegate = new InMemoryTaskStore();
        private final List<String> requestedIds = new ArrayList<>();

        List<String> requestedIds() {
            return List.copyOf(requestedIds);
        }

        /** {@inheritDoc} */
        @Override
        public void save(Task task, boolean initial) {
            delegate.save(task, initial);
        }

        /** {@inheritDoc} */
        @Override
        public Task get(String taskId) {
            requestedIds.add(taskId);
            return delegate.get(taskId);
        }

        /** {@inheritDoc} */
        @Override
        public void delete(String taskId) {
            delegate.delete(taskId);
        }

        /** {@inheritDoc} */
        @Override
        public ListTasksResult list(ListTasksParams params) {
            return delegate.list(params);
        }
    }

    /**
     * Minimal outbox fixture that records the durable-enqueue boundary owned by FEAT-017.
     */
    static final class RecordingOutbox implements ForwardingOutboxPort {
        private final Map<String, ForwardingEnvelope> envelopes = new LinkedHashMap<>();

        List<ForwardingEnvelope> envelopes() {
            return new ArrayList<>(envelopes.values());
        }

        /** {@inheritDoc} */
        @Override
        public ForwardingReceipt enqueue(ForwardingEnvelope envelope, String sourceServiceId, String targetServiceId,
                long nowMillisEpoch) {
            envelopes.putIfAbsent(envelope.tenantId() + "|" + envelope.messageId().value(), envelope);
            return ForwardingReceipt.accepted(envelope.messageId(), envelope.tenantId(), nowMillisEpoch);
        }

        /** {@inheritDoc} */
        @Override
        public ForwardingStatus.Outbox markAcked(ForwardingMessageId id, String tenantId, String leaseOwner) {
            throw new UnsupportedOperationException("dispatch state is owned by agent-bus");
        }

        /** {@inheritDoc} */
        @Override
        public ForwardingStatus.Outbox scheduleRetry(ForwardingMessageId id, String tenantId, String leaseOwner,
                ForwardingFailureCode code, long nextAttemptAtMillisEpoch) {
            throw new UnsupportedOperationException("dispatch state is owned by agent-bus");
        }

        /** {@inheritDoc} */
        @Override
        public ForwardingStatus.Outbox moveToDlq(ForwardingMessageId id, String tenantId, String leaseOwner,
                ForwardingFailureCode code) {
            throw new UnsupportedOperationException("dispatch state is owned by agent-bus");
        }

        /** {@inheritDoc} */
        @Override
        public ForwardingStatus.Outbox markExpired(ForwardingMessageId id, String tenantId, String leaseOwner) {
            throw new UnsupportedOperationException("dispatch state is owned by agent-bus");
        }

        /** {@inheritDoc} */
        @Override
        public ForwardingStatus.Outbox statusOf(ForwardingMessageId id, String tenantId) {
            return ForwardingStatus.Outbox.PENDING;
        }
    }
}
