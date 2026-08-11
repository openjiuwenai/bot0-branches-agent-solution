/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.AgentBusRequestSubmitter;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.service.app.controller.a2a.client.A2ATaskSubscriptionClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallEventConsumer;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcomeMapper;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.spec.A2AException;
import org.a2aproject.sdk.spec.TaskState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runtime-to-Runtime caller that uses Agent Bus for its control plane.
 *
 * @since 2026-08-04
 */
public final class AgentBusRemoteAgentCaller implements RemoteAgentCaller {
    private static final String CAPABILITY = "a2a-call";
    private static final int MAX_STREAM_REFERENCE_REFRESHES = 1;

    private final RuntimeRdcClient registry;
    private final AgentBusRequestSubmitter requestSubmitter;
    private final RemoteCallOutcomeMapper outcomeMapper;
    private final RemoteCallEventConsumer eventConsumer;
    private final AgentBusRequestEncoder requestEncoder = new AgentBusRequestEncoder();
    private final AgentBusProjectionDecoder projectionDecoder = new AgentBusProjectionDecoder();
    private final Map<String, PendingRemoteCall> pending = new ConcurrentHashMap<>();
    private final Executor executor;
    private final StreamSubscriber streamSubscriber;
    private final String tenantId;
    private final String sourceServiceId;
    private final long responseTimeoutMillis;

    /**
     * Creates an Agent Bus caller with the standard A2A subscription client.
     *
     * @param registry registry discovery client
     * @param requestSubmitter Agent Bus high-level request submitter
     * @param executor blocking discovery/submission executor
     * @param tenantId trusted tenant scope
     * @param sourceServiceId local Runtime service identifier
     * @param responseTimeoutMillis overall response timeout
     */
    public AgentBusRemoteAgentCaller(RuntimeRdcClient registry, AgentBusRequestSubmitter requestSubmitter,
            Executor executor,
            String tenantId, String sourceServiceId, long responseTimeoutMillis) {
        this(registry, requestSubmitter, executor, tenantId, sourceServiceId, responseTimeoutMillis,
                new A2ATaskSubscriptionClient());
    }

    /**
     * Creates an Agent Bus caller that reuses the Runtime A2A Task subscription client.
     *
     * @param registry registry discovery client
     * @param requestSubmitter Agent Bus high-level request submitter
     * @param executor blocking discovery/submission executor
     * @param tenantId trusted tenant scope
     * @param sourceServiceId local Runtime service identifier
     * @param responseTimeoutMillis overall response timeout
     * @param subscriptionClient standard A2A Task subscription client
     */
    public AgentBusRemoteAgentCaller(RuntimeRdcClient registry, AgentBusRequestSubmitter requestSubmitter,
            Executor executor, String tenantId, String sourceServiceId, long responseTimeoutMillis,
            A2ATaskSubscriptionClient subscriptionClient) {
        this(registry, requestSubmitter, executor, tenantId, sourceServiceId, responseTimeoutMillis,
                subscriber(subscriptionClient));
    }

    AgentBusRemoteAgentCaller(RuntimeRdcClient registry, AgentBusRequestSubmitter requestSubmitter,
            Executor executor, String tenantId, String sourceServiceId, long responseTimeoutMillis,
            StreamSubscriber streamSubscriber) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.requestSubmitter = Objects.requireNonNull(requestSubmitter, "requestSubmitter is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.tenantId = require(tenantId, "tenantId");
        this.sourceServiceId = require(sourceServiceId, "sourceServiceId");
        this.responseTimeoutMillis = positive(responseTimeoutMillis, "responseTimeoutMillis");
        this.streamSubscriber = Objects.requireNonNull(streamSubscriber, "streamSubscriber is required");
        this.outcomeMapper = new RemoteCallOutcomeMapper();
        this.eventConsumer = new RemoteCallEventConsumer();
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
            RuntimeRdcClient.ResolvedRoute resolved = call.isCallerStreaming()
                    ? registry.resolve(tenantId, route.routeHandle()) : null;
            if (result.isDone()) {
                return;
            }
            String correlationId = UUID.randomUUID().toString();
            String idempotencyKey = UUID.randomUUID().toString();
            long now = System.currentTimeMillis();
            long deadline = now + responseTimeoutMillis;
            PendingRemoteCall pendingCall = new PendingRemoteCall(result, taskIdObserver, streamObserver,
                    call.isCallerStreaming(), route, resolved == null ? null : resolved.endpointUrl(),
                    correlationId, idempotencyKey, deadline);
            pending.put(correlationId, pendingCall);
            result.whenComplete((outcome, failure) -> {
                pending.remove(correlationId, pendingCall);
                pendingCall.closeStream();
            });
            ForwardingEnvelope envelope = creationEnvelope(call, route, correlationId, idempotencyKey, deadline);
            if (result.isDone()) {
                pending.remove(correlationId, pendingCall);
                return;
            }
            submit(envelope, result);
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException failure) {
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

    private ForwardingEnvelope creationEnvelope(RemoteCall call, RuntimeRdcClient.RouteCandidate route,
            String correlationId, String idempotencyKey, long deadline) {
        String requestId = "bus-" + UUID.randomUUID();
        String payload = requestEncoder.encode(call, requestId, tenantId);
        EnvelopeIdentity identity = new EnvelopeIdentity(correlationId, idempotencyKey,
                traceId(call.metadata(), correlationId), requestId, deadline);
        return envelope(AgentBusEventType.A2A_CALL_REQUESTED, route, identity, payload);
    }

    private ForwardingEnvelope subscriptionEnvelope(PendingRemoteCall call) {
        String requestId = "bus-stream-" + UUID.randomUUID();
        String payload = requestEncoder.encodeSubscription(call.taskId, requestId, tenantId);
        EnvelopeIdentity identity = new EnvelopeIdentity(call.correlationId, call.idempotencyKey,
                call.correlationId, requestId, call.deadline);
        return envelope(AgentBusEventType.A2A_STREAM_SUBSCRIBE_REQUESTED, call.route, identity, payload);
    }

    private ForwardingEnvelope envelope(AgentBusEventType eventType, RuntimeRdcClient.RouteCandidate route,
            EnvelopeIdentity identity, String payload) {
        return new ForwardingEnvelope(new ForwardingMessageId(identity.requestId), eventType, tenantId,
                identity.traceId, identity.correlationId, identity.idempotencyKey,
                new ForwardingRouteHandle(route.routeHandle(), tenantId), CAPABILITY, sourceServiceId,
                route.serviceId(), identity.deadline, ForwardingEnvelope.PayloadPolicy.DATA_BEARING,
                null, payload, sourceServiceId);
    }

    private void submit(ForwardingEnvelope envelope, CompletableFuture<RemoteCallOutcome> result) {
        ForwardingReceipt receipt = requestSubmitter.submit(envelope);
        if (!receipt.accepted()) {
            result.completeExceptionally(new IllegalStateException(
                    "Agent Bus rejected remote call submission: " + receipt.failureCode()));
        }
    }

    private void apply(AgentBusEventType eventType, PendingRemoteCall call,
            AgentBusProjectionDecoder.DecodedProjection projection) {
        if (eventType == null) {
            throw new IllegalArgumentException("Response projection eventType is required");
        }
        call.captureTaskId(projection.value("taskId"));
        switch (eventType) {
            case A2A_CALL_ACCEPTED -> {
                return;
            }
            case A2A_STREAM_READY -> openStream(call, requiredProjection(projection, "streamRef"));
            case A2A_CALL_REJECTED -> completeState(call, projection, TaskState.TASK_STATE_REJECTED);
            case A2A_CALL_FAILED -> completeState(call, projection, TaskState.TASK_STATE_FAILED);
            case A2A_CALL_RESPONSE, A2A_CALL_INPUT_REQUIRED, A2A_CALL_TERMINAL ->
                    completeA2a(call, projection, eventType);
            default -> throw new IllegalArgumentException("Unexpected Agent Bus response type: " + eventType);
        }
    }

    private void openStream(PendingRemoteCall call, String streamReference) {
        int generation = call.beginStream(streamReference);
        if (generation < 0) {
            return;
        }
        try {
            A2ATaskSubscriptionClient.TaskSubscriptionRequest request =
                    new A2ATaskSubscriptionClient.TaskSubscriptionRequest(call.endpointUrl, call.taskId,
                            streamReference);
            StreamHandle subscription = streamSubscriber.subscribe(request,
                    event -> acceptStreamEvent(call, event),
                    () -> streamFailed(call, generation,
                            new IllegalStateException("SubscribeToTask stream closed before a terminal event")),
                    failure -> streamFailed(call, generation, failure));
            call.installStream(generation, subscription);
        } catch (A2AException | IllegalArgumentException | IllegalStateException failure) {
            streamFailed(call, generation, failure);
        }
    }

    private void acceptStreamEvent(PendingRemoteCall call, ClientEvent event) {
        eventConsumer.accept(event, call.result, call.streamObserver, call.taskIdObserver, false);
    }

    private void streamFailed(PendingRemoteCall call, int generation, Throwable failure) {
        if (!call.beginStreamReferenceRefresh(generation)) {
            if (!call.result.isDone()) {
                call.result.completeExceptionally(failure == null
                        ? new IllegalStateException("SubscribeToTask failed") : failure);
            }
            return;
        }
        try {
            executor.execute(() -> refreshStreamReference(call));
        } catch (RejectedExecutionException rejected) {
            call.result.completeExceptionally(rejected);
        }
    }

    private void refreshStreamReference(PendingRemoteCall call) {
        if (call.result.isDone()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            if (now >= call.deadline) {
                call.result.completeExceptionally(new IllegalStateException("Remote call deadline exceeded"));
                return;
            }
            submit(subscriptionEnvelope(call), call.result);
        } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException failure) {
            call.result.completeExceptionally(failure);
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
                ? TaskState.TASK_STATE_INPUT_REQUIRED : state(projection.value("status")).orElse(null);
        completeState(call, projection, fallback);
    }

    private void completeState(PendingRemoteCall call, AgentBusProjectionDecoder.DecodedProjection projection,
            TaskState state) {
        String taskId = projection.value("taskId");
        String reason = firstNonBlank(projection.value("reason"), projection.value("errorCode"));
        outcomeMapper.mapTask(taskId, state, reason, null, false).ifPresent(call.result::complete);
    }

    private static String requiredProjection(AgentBusProjectionDecoder.DecodedProjection projection, String name) {
        String value = projection.value(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required in A2A stream projection");
        }
        return value;
    }

    private static Optional<TaskState> state(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.startsWith("TASK_STATE_")) {
            normalized = "TASK_STATE_" + normalized;
        }
        try {
            return Optional.of(TaskState.valueOf(normalized));
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

    private static StreamSubscriber subscriber(A2ATaskSubscriptionClient subscriptionClient) {
        A2ATaskSubscriptionClient client = Objects.requireNonNull(subscriptionClient,
                "subscriptionClient is required");
        return (request, eventConsumer, completionHandler, errorHandler) -> {
            A2ATaskSubscriptionClient.TaskSubscription subscription = client.subscribe(request, eventConsumer,
                    completionHandler, errorHandler);
            return subscription::close;
        };
    }

    private record EnvelopeIdentity(String correlationId, String idempotencyKey, String traceId,
            String requestId, long deadline) {
    }

    @FunctionalInterface
    interface StreamSubscriber {
        /**
         * Opens the standard A2A Task event stream.
         *
         * @param request subscription coordinates
         * @param eventConsumer decoded event consumer
         * @param completionHandler completion callback
         * @param errorHandler error callback
         * @return local stream handle
         */
        StreamHandle subscribe(
                A2ATaskSubscriptionClient.TaskSubscriptionRequest request,
                Consumer<ClientEvent> eventConsumer, Runnable completionHandler, Consumer<Throwable> errorHandler);
    }

    @FunctionalInterface
    interface StreamHandle {
        /** Closes the local stream subscription. */
        void close();
    }

    private static final class PendingRemoteCall {
        private final CompletableFuture<RemoteCallOutcome> result;
        private final Consumer<String> taskIdObserver;
        private final QueryStreamObserver streamObserver;
        private final boolean streaming;
        private final RuntimeRdcClient.RouteCandidate route;
        private final String endpointUrl;
        private final String correlationId;
        private final String idempotencyKey;
        private final long deadline;
        private String taskId;
        private String streamReference;
        private int streamGeneration;
        private int streamReferenceRefreshes;
        private StreamHandle subscription;

        private PendingRemoteCall(CompletableFuture<RemoteCallOutcome> result,
                Consumer<String> taskIdObserver, QueryStreamObserver streamObserver, boolean streaming,
                RuntimeRdcClient.RouteCandidate route, String endpointUrl, String correlationId,
                String idempotencyKey, long deadline) {
            this.result = result;
            this.taskIdObserver = taskIdObserver;
            this.streamObserver = streamObserver;
            this.streaming = streaming;
            this.route = route;
            this.endpointUrl = endpointUrl;
            this.correlationId = correlationId;
            this.idempotencyKey = idempotencyKey;
            this.deadline = deadline;
        }

        private synchronized void captureTaskId(String value) {
            if (value == null || value.isBlank() || value.equals(taskId)) {
                return;
            }
            taskId = value;
            if (taskIdObserver != null) {
                taskIdObserver.accept(value);
            }
        }

        private synchronized int beginStream(String value) {
            if (!streaming || result.isDone() || taskId == null || endpointUrl == null
                    || (value.equals(streamReference) && subscription != null)) {
                return -1;
            }
            closeSubscription();
            streamReference = value;
            return ++streamGeneration;
        }

        private synchronized void installStream(int generation,
                StreamHandle value) {
            if (generation != streamGeneration || result.isDone()) {
                close(value);
                return;
            }
            subscription = value;
        }

        private synchronized boolean beginStreamReferenceRefresh(int generation) {
            if (generation != streamGeneration || result.isDone()) {
                return false;
            }
            streamGeneration++;
            closeSubscription();
            streamReference = null;
            if (streamReferenceRefreshes >= MAX_STREAM_REFERENCE_REFRESHES) {
                return false;
            }
            streamReferenceRefreshes++;
            return true;
        }

        private synchronized void closeStream() {
            streamGeneration++;
            closeSubscription();
        }

        private void closeSubscription() {
            if (subscription != null) {
                close(subscription);
                subscription = null;
            }
        }

        private static void close(StreamHandle value) {
            value.close();
        }
    }
}
