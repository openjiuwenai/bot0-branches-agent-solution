/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcomeMapper;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runtime-to-Runtime caller that uses Agent Bus for its control plane.
 *
 * @since 2026-08-04
 */
public final class AgentBusRemoteAgentCaller implements RemoteAgentCaller {
    private static final String CAPABILITY = "a2a-call";

    private final RuntimeRdcClient registry;
    private final ForwardingOutboxPort outbox;
    private final RemoteCallOutcomeMapper outcomeMapper;
    private final AgentBusRequestEncoder requestEncoder = new AgentBusRequestEncoder();
    private final AgentBusProjectionDecoder projectionDecoder = new AgentBusProjectionDecoder();
    private final Map<String, PendingRemoteCall> pending = new ConcurrentHashMap<>();
    private final Executor executor;
    private final String tenantId;
    private final String sourceServiceId;
    private final long responseTimeoutMillis;

    /**
     * Creates an Agent Bus caller.
     *
     * @param registry registry discovery client
     * @param outbox Agent Bus request outbox
     * @param executor blocking discovery/enqueue executor
     * @param tenantId trusted tenant scope
     * @param sourceServiceId local Runtime service identifier
     * @param responseTimeoutMillis overall response timeout
     */
    public AgentBusRemoteAgentCaller(RuntimeRdcClient registry, ForwardingOutboxPort outbox, Executor executor,
            String tenantId, String sourceServiceId, long responseTimeoutMillis) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.tenantId = require(tenantId, "tenantId");
        this.sourceServiceId = require(sourceServiceId, "sourceServiceId");
        this.responseTimeoutMillis = positive(responseTimeoutMillis, "responseTimeoutMillis");
        this.outcomeMapper = new RemoteCallOutcomeMapper();
    }

    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, QueryStreamObserver streamObserver,
            Consumer<String> remoteTaskIdObserver) {
        Objects.requireNonNull(call, "call is required");
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        result.orTimeout(responseTimeoutMillis, TimeUnit.MILLISECONDS);
        executor.execute(() -> startCall(call, streamObserver, remoteTaskIdObserver, result));
        return result;
    }

    @Override
    public boolean supported(String agentName) {
        return agentName != null && !agentName.isBlank();
    }

    /**
     * Applies one response message polled by the caller lifecycle.
     *
     * @param message Agent Bus response
     * @return true when the response matched a pending call
     */
    public boolean accept(BrokerInboundMessage message) {
        PendingRemoteCall call = pending.get(message.correlationId());
        if (call == null) {
            return false;
        }
        AgentBusProjectionDecoder.DecodedProjection projection = projectionDecoder.decode(message.inlinePayload());
        apply(message.eventType(), call, projection);
        return true;
    }

    /**
     * Completes a matching call exceptionally after a deterministic protocol error.
     *
     * @param correlationId response correlation identifier
     * @param failure protocol failure
     */
    public void failProtocol(String correlationId, RuntimeException failure) {
        PendingRemoteCall call = pending.get(correlationId);
        if (call != null) {
            call.result.completeExceptionally(failure);
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private void startCall(RemoteCall call, QueryStreamObserver streamObserver,
            Consumer<String> taskIdObserver, CompletableFuture<RemoteCallOutcome> result) {
        try {
            RuntimeRdcClient.RouteCandidate route = firstRoute(call.agentName());
            if (result.isDone()) {
                return;
            }
            String correlationId = UUID.randomUUID().toString();
            String idempotencyKey = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            PendingRemoteCall pendingCall = new PendingRemoteCall(result, taskIdObserver, streamObserver);
            pending.put(correlationId, pendingCall);
            result.whenComplete((outcome, failure) -> pending.remove(correlationId, pendingCall));
            ForwardingEnvelope envelope = envelope(call, route, correlationId, idempotencyKey, now);
            if (result.isDone()) {
                pending.remove(correlationId, pendingCall);
                return;
            }
            ForwardingReceipt receipt = outbox.enqueue(envelope, sourceServiceId, route.serviceId(), now);
            if (!receipt.accepted()) {
                result.completeExceptionally(new IllegalStateException(
                        "Agent Bus rejected remote call enqueue: " + receipt.failureCode()));
            }
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    private RuntimeRdcClient.RouteCandidate firstRoute(String agentName) {
        List<RuntimeRdcClient.RouteCandidate> candidates = registry.findCandidates(tenantId,
                require(agentName, "agentName"));
        if (candidates.isEmpty()) {
            throw new IllegalStateException("No Runtime route registered for agentId=" + agentName);
        }
        return candidates.get(0);
    }

    private ForwardingEnvelope envelope(RemoteCall call, RuntimeRdcClient.RouteCandidate route,
            String correlationId, String idempotencyKey, long now) {
        String requestId = "bus-" + UUID.randomUUID();
        String payload = requestEncoder.encode(call, requestId, tenantId);
        return new ForwardingEnvelope(new ForwardingMessageId(requestId), AgentBusEventType.A2A_CALL_REQUESTED,
                tenantId, traceId(call.metadata(), correlationId), correlationId, idempotencyKey,
                new ForwardingRouteHandle(route.routeHandle(), tenantId), CAPABILITY, sourceServiceId,
                route.serviceId(), now + responseTimeoutMillis, ForwardingEnvelope.PayloadPolicy.DATA_BEARING,
                null, payload, sourceServiceId);
    }

    private void apply(AgentBusEventType eventType, PendingRemoteCall call,
            AgentBusProjectionDecoder.DecodedProjection projection) {
        if (eventType == null) {
            throw new IllegalArgumentException("Response projection eventType is required");
        }
        call.captureTaskId(projection.value("taskId"));
        switch (eventType) {
            case A2A_CALL_ACCEPTED, A2A_STREAM_READY -> {
                return;
            }
            case A2A_CALL_REJECTED -> completeState(call, projection, TaskState.TASK_STATE_REJECTED);
            case A2A_CALL_FAILED -> completeState(call, projection, TaskState.TASK_STATE_FAILED);
            case A2A_CALL_RESPONSE, A2A_CALL_INPUT_REQUIRED, A2A_CALL_TERMINAL ->
                    completeA2a(call, projection, eventType);
            default -> throw new IllegalArgumentException("Unexpected Agent Bus response type: " + eventType);
        }
    }

    private void completeA2a(PendingRemoteCall call, AgentBusProjectionDecoder.DecodedProjection projection,
            AgentBusEventType eventType) {
        if (projection.task() != null) {
            call.captureTaskId(projection.task().id());
            outcomeMapper.mapTask(projection.task(), false).ifPresent(call.result::complete);
            return;
        }
        if (projection.message() != null) {
            call.captureTaskId(projection.message().taskId());
            outcomeMapper.mapMessage(projection.message()).ifPresent(call.result::complete);
            return;
        }
        TaskState fallback = eventType == AgentBusEventType.A2A_CALL_INPUT_REQUIRED
                ? TaskState.TASK_STATE_INPUT_REQUIRED : state(projection.value("status"));
        completeState(call, projection, fallback);
    }

    private void completeState(PendingRemoteCall call, AgentBusProjectionDecoder.DecodedProjection projection,
            TaskState state) {
        String taskId = projection.value("taskId");
        String reason = firstNonBlank(projection.value("reason"), projection.value("errorCode"));
        outcomeMapper.mapTask(taskId, state, reason, null, false).ifPresent(call.result::complete);
    }

    private static TaskState state(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("TASK_STATE_")) {
            normalized = "TASK_STATE_" + normalized;
        }
        try {
            return TaskState.valueOf(normalized);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Unsupported A2A task status: " + value, failure);
        }
    }

    private static String traceId(Map<String, Object> metadata, String fallback) {
        Object value = metadata.get("traceId");
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static long positive(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero");
        }
        return value;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static final class PendingRemoteCall {
        private final CompletableFuture<RemoteCallOutcome> result;
        private final Consumer<String> taskIdObserver;
        @SuppressWarnings("unused")
        private final QueryStreamObserver streamObserver;
        private volatile String taskId;

        private PendingRemoteCall(CompletableFuture<RemoteCallOutcome> result,
                Consumer<String> taskIdObserver, QueryStreamObserver streamObserver) {
            this.result = result;
            this.taskIdObserver = taskIdObserver;
            this.streamObserver = streamObserver;
        }

        private void captureTaskId(String value) {
            if (value == null || value.isBlank() || value.equals(taskId)) {
                return;
            }
            taskId = value;
            if (taskIdObserver != null) {
                taskIdObserver.accept(value);
            }
        }
    }
}
