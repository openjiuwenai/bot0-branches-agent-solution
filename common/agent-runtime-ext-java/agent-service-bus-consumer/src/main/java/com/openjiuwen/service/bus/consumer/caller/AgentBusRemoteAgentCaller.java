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
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.bus.consumer.projection.AgentBusProjectionJsonCodec;

import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.A2AException;
import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOG = LoggerFactory.getLogger(AgentBusRemoteAgentCaller.class);
    private static final String CAPABILITY = "a2a-call";
    private static final int MAX_STREAM_REFERENCE_REFRESHES = 1;
    private static final String CALLBACK_URL_METADATA = "runtime.a2a.callbackUrl";

    /**
     * Time a call waits for the authoritative Bus terminal projection after the data-plane
     * subscription is definitively gone.
     */
    private static final long DEFAULT_STREAM_FAILURE_GRACE_MILLIS = 2_000L;

    private final RuntimeRdcClient registry;
    private final AgentBusRequestSubmitter requestSubmitter;
    private final RemoteCallOutcomeMapper outcomeMapper;
    private final BusRemoteCallEventConsumer eventConsumer;
    private final AgentBusRequestEncoder requestEncoder = new AgentBusRequestEncoder();
    private final AgentBusProjectionDecoder projectionDecoder = new AgentBusProjectionDecoder();
    private final Map<String, PendingRemoteCall> pending = new ConcurrentHashMap<>();
    private final Executor executor;
    private final StreamSubscriber streamSubscriber;
    private final String tenantId;
    private final String sourceServiceId;
    private final long responseTimeoutMillis;
    private final long streamFailureGraceMillis;

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
                subscriber(new BusTaskSubscriptionClient(new A2ATaskSubscriptionClient())));
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
                subscriber(new BusTaskSubscriptionClient(subscriptionClient)));
    }

    AgentBusRemoteAgentCaller(RuntimeRdcClient registry, AgentBusRequestSubmitter requestSubmitter,
            Executor executor, String tenantId, String sourceServiceId, long responseTimeoutMillis,
            StreamSubscriber streamSubscriber) {
        this(registry, requestSubmitter, executor, tenantId, sourceServiceId, responseTimeoutMillis,
                DEFAULT_STREAM_FAILURE_GRACE_MILLIS, streamSubscriber);
    }

    AgentBusRemoteAgentCaller(RuntimeRdcClient registry, AgentBusRequestSubmitter requestSubmitter,
            Executor executor, String tenantId, String sourceServiceId, long responseTimeoutMillis,
            long streamFailureGraceMillis, StreamSubscriber streamSubscriber) {
        this.registry = Objects.requireNonNull(registry, "registry is required");
        this.requestSubmitter = Objects.requireNonNull(requestSubmitter, "requestSubmitter is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.tenantId = require(tenantId, "tenantId");
        this.sourceServiceId = require(sourceServiceId, "sourceServiceId");
        this.responseTimeoutMillis = positive(responseTimeoutMillis, "responseTimeoutMillis");
        this.streamFailureGraceMillis = Math.max(0L, streamFailureGraceMillis);
        this.streamSubscriber = Objects.requireNonNull(streamSubscriber, "streamSubscriber is required");
        this.outcomeMapper = new RemoteCallOutcomeMapper();
        this.eventConsumer = new BusRemoteCallEventConsumer();
    }

    @Override
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            RemoteAgentCaller.EventObserver eventObserver) {
        Objects.requireNonNull(call, "call is required");
        Objects.requireNonNull(eventObserver, "eventObserver is required");
        if (isCallbackRequested(call)) {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("Agent Bus caller does not support callback mode"));
        }
        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        result.orTimeout(responseTimeoutMillis, TimeUnit.MILLISECONDS);
        executor.execute(() -> startCall(call, eventObserver, result));
        return result;
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
        try {
            validateResponseEnvelope(message, call);
            AgentBusProjectionJsonCodec.DecodedProjection projection = projectionDecoder.decode(
                    message.inlinePayload(), message.eventType().name());
            validateResponseId(projection, call);
            if (projection.responseError() != null) {
                call.result.completeExceptionally(remoteProtocolFailure(projection.responseError()));
                return true;
            }
            apply(message.eventType(), call, projection);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            call.result.completeExceptionally(failure);
        }
        return true;
    }

    /**
     * Completes a matching call exceptionally after a deterministic protocol error.
     *
     * <p>A missing {@code correlationId} is one of the protocol errors that lead here, so it must be
     * tolerated: {@code pending} is a {@code ConcurrentHashMap} and would throw on a null lookup,
     * and that throw would escape the caller's own catch block and kill its polling thread.
     *
     * @param correlationId response correlation identifier, may be null for malformed responses
     * @param failure protocol failure
     */
    public void failProtocol(String correlationId, RuntimeException failure) {
        if (correlationId == null) {
            return;
        }
        PendingRemoteCall call = pending.get(correlationId);
        if (call != null) {
            call.result.completeExceptionally(failure);
        }
    }

    int pendingCount() {
        return pending.size();
    }

    private void startCall(RemoteCall call, RemoteAgentCaller.EventObserver eventObserver,
            CompletableFuture<RemoteCallOutcome> result) {
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
            ForwardingEnvelope envelope = creationEnvelope(call, route, correlationId, idempotencyKey, deadline);
            PendingRemoteCall pendingCall = new PendingRemoteCall(result, eventObserver, call.isCallerStreaming(),
                    route, resolved == null ? null : resolved.endpointUrl(),
                    correlationId, idempotencyKey, envelope.messageId().value(), deadline);
            pending.put(correlationId, pendingCall);
            result.whenComplete((outcome, failure) -> {
                pending.remove(correlationId, pendingCall);
                pendingCall.closeStream();
            });
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
        call.expectResponseId(requestId);
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
            AgentBusProjectionJsonCodec.DecodedProjection projection) {
        if (eventType == null) {
            throw new IllegalArgumentException("Response projection eventType is required");
        }
        call.captureTaskId(projection.taskId());
        switch (eventType) {
            case A2A_CALL_ACCEPTED -> {
                return;
            }
            case A2A_STREAM_READY -> openStream(call, requiredProjection(projection.streamRef(), "streamRef"));
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
            BusTaskSubscriptionClient.SubscriptionRequest request =
                    new BusTaskSubscriptionClient.SubscriptionRequest(call.endpointUrl, call.taskId,
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
        eventConsumer.accept(event, call.result, call.eventObserver, call);
    }

    private void streamFailed(PendingRemoteCall call, int generation, Throwable failure) {
        if (call.result.isDone()) {
            return;
        }
        if (isRemoteTaskAlreadyTerminal(failure)) {
            // The callee refused the subscription because its Task is already terminal, so the
            // authoritative A2A_CALL_TERMINAL projection is already in flight on the Bus control
            // plane. Refreshing the stream reference would re-hit the same terminal guard, and it
            // would additionally rewrite expectedResponseId so that the late TERMINAL fails
            // validateResponseId. Degrade to non-streaming instead and let the control plane finish
            // the call; result.orTimeout(responseTimeoutMillis) remains the backstop.
            if (call.abandonStream(generation)) {
                LOG.warn("SubscribeToTask refused for correlationId={} taskId={}: remote Task is already "
                        + "terminal; degrading to non-streaming and waiting for the Bus terminal projection",
                        call.correlationId, call.taskId);
            }
            return;
        }
        switch (call.beginStreamReferenceRefresh(generation)) {
            // STALE: another callback already advanced the generation; that path owns the outcome.
            case STALE -> LOG.debug("Stream failure for correlationId={} taskId={} is stale; "
                    + "a newer callback already owns the outcome", call.correlationId, call.taskId);
            case EXHAUSTED -> failAfterGrace(call, failure);
            case REFRESH -> {
                try {
                    executor.execute(() -> refreshStreamReference(call));
                } catch (RejectedExecutionException rejected) {
                    call.result.completeExceptionally(rejected);
                }
            }
            default -> throw new IllegalStateException("Unexpected stream refresh decision");
        }
    }

    /**
     * Fails the call, but only after giving the Bus control plane a last chance to land its terminal
     * projection.
     *
     * <p>The data plane is gone at this point, yet the Bus response for the same call may still be a
     * few milliseconds behind; failing synchronously would turn an already-completed remote Task into
     * a caller-side error.
     *
     * @param call pending call whose subscription is definitively gone
     * @param failure the subscription failure to report if nothing arrives in time
     */
    private void failAfterGrace(PendingRemoteCall call, Throwable failure) {
        Throwable cause = failure == null
                ? new IllegalStateException("SubscribeToTask failed") : failure;
        long grace = Math.min(streamFailureGraceMillis,
                Math.max(0L, call.deadline - System.currentTimeMillis()));
        if (grace <= 0L) {
            call.result.completeExceptionally(cause);
            return;
        }
        LOG.warn("SubscribeToTask exhausted stream reference refreshes for correlationId={} taskId={}; "
                + "waiting {}ms for a Bus terminal projection before failing",
                call.correlationId, call.taskId, grace);
        try {
            CompletableFuture.delayedExecutor(grace, TimeUnit.MILLISECONDS, executor)
                    .execute(() -> call.result.completeExceptionally(cause));
        } catch (RejectedExecutionException rejected) {
            call.result.completeExceptionally(cause);
        }
    }

    /**
     * Detects the A2A error the callee raises when the Task being subscribed to is already terminal.
     *
     * <p>Per the A2A specification {@code SubscribeToTask} on a terminal Task must answer
     * {@code UnsupportedOperationError} ({@code -32004}); the SDK rebuilds that JSON-RPC error into an
     * {@link A2AError} before handing it to the stream error handler.
     *
     * @param failure subscription failure reported by the stream subscriber
     * @return true when the failure means "the remote Task already finished"
     */
    private static boolean isRemoteTaskAlreadyTerminal(Throwable failure) {
        for (Throwable cursor = failure; cursor != null && cursor != cursor.getCause();
                cursor = cursor.getCause()) {
            if (cursor instanceof A2AError error && error.getCode() != null
                    && error.getCode() == A2AErrorCodes.UNSUPPORTED_OPERATION.code()) {
                return true;
            }
        }
        return false;
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

    private void completeA2a(PendingRemoteCall call, AgentBusProjectionJsonCodec.DecodedProjection projection,
            AgentBusEventType eventType) {
        if (projection.task() != null) {
            call.captureTaskId(projection.task().id());
            eventConsumer.accept(new TaskEvent(projection.task()), call.result, call.eventObserver, call);
            return;
        }
        if (projection.message() != null) {
            call.captureTaskId(projection.message().taskId());
            outcomeMapper.mapMessage(projection.message()).ifPresent(call.result::complete);
            return;
        }
        TaskState fallback = eventType == AgentBusEventType.A2A_CALL_INPUT_REQUIRED
                ? TaskState.TASK_STATE_INPUT_REQUIRED : state(projection.status()).orElse(null);
        if (fallback == null) {
            throw new IllegalArgumentException("A2A response result is neither a Task nor a Message");
        }
        completeState(call, projection, fallback);
    }

    private void completeState(PendingRemoteCall call, AgentBusProjectionJsonCodec.DecodedProjection projection,
            TaskState state) {
        String reason = firstNonBlank(projection.reason(), projection.errorCode());
        outcomeMapper.mapTask(projection.taskId(), state, reason, null, false)
                .ifPresent(call.result::complete);
    }

    private static boolean isCallbackRequested(RemoteCall call) {
        Object rawUrl = call.metadata().get(CALLBACK_URL_METADATA);
        return rawUrl instanceof String url && !url.isBlank();
    }

    private static String requiredProjection(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required in A2A stream projection");
        }
        return value;
    }

    /**
     * Validates that an inbound response envelope really belongs to {@code call}.
     *
     * <p>The responder identity is checked against {@code routeHandle}, NOT
     * {@code sourceServiceId}: the relay overwrites {@code sourceServiceId} with its own
     * serviceId on every hop (see {@code EventBusRelayWorker#buildHop2Envelope} and the
     * {@code BrokerInboundMessage#originalCaller} javadoc), so on the Bus it always reads
     * {@code eventbus-*} and can never equal the callee's serviceId. {@code routeHandle} is
     * passed through unchanged and encodes the resolved target ({@code tenantId} /
     * {@code agentId} / {@code serviceId} / {@code instanceId}), which is exactly the
     * responder identity we want to pin. It is nullable for control-only / legacy messages,
     * in which case the check is skipped — the tenant / target / correlation triple below
     * still bounds the match.
     *
     * @param message inbound response envelope
     * @param call the pending call the response was matched to by correlationId
     */
    private void validateResponseEnvelope(BrokerInboundMessage message, PendingRemoteCall call) {
        if (!tenantId.equals(message.tenantId())) {
            throw new IllegalArgumentException("Response tenant does not match pending call");
        }
        if (message.routeHandle() != null && !call.route.routeHandle().equals(message.routeHandle())) {
            throw new IllegalArgumentException("Response route does not match pending call");
        }
        if (!sourceServiceId.equals(message.targetServiceId())) {
            throw new IllegalArgumentException("Response target does not match local Runtime");
        }
        if (!call.correlationId.equals(message.correlationId())) {
            throw new IllegalArgumentException("Response correlation does not match pending call");
        }
    }

    private static void validateResponseId(AgentBusProjectionJsonCodec.DecodedProjection projection,
            PendingRemoteCall call) {
        if (projection.responseId() == null) {
            return;
        }
        if (!projection.responseId().isTextual()
                || !call.expectedResponseId().equals(projection.responseId().textValue())) {
            throw new IllegalArgumentException("JSON-RPC response id does not match pending request");
        }
    }

    private static IllegalStateException remoteProtocolFailure(com.fasterxml.jackson.databind.JsonNode error) {
        String code = error.has("code") ? error.get("code").asText() : "unknown";
        String message = error.has("message") ? error.get("message").asText() : "Remote A2A protocol error";
        return new IllegalStateException("Remote A2A protocol error code=" + code + " message=" + message);
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

    private static StreamSubscriber subscriber(BusTaskSubscriptionClient subscriptionClient) {
        BusTaskSubscriptionClient client = Objects.requireNonNull(subscriptionClient,
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

    /** Outcome of a stream reference refresh attempt. */
    private enum RefreshDecision {
        /** The reporting callback no longer owns the current stream generation. */
        STALE,
        /** A new stream reference must be requested from the callee. */
        REFRESH,
        /** No refresh budget is left for this call. */
        EXHAUSTED
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
                BusTaskSubscriptionClient.SubscriptionRequest request,
                Consumer<ClientEvent> eventConsumer, Runnable completionHandler, Consumer<Throwable> errorHandler);
    }

    @FunctionalInterface
    interface StreamHandle {
        /**
         * Closes the local stream subscription.
         */
        void close();
    }

    private static final class PendingRemoteCall implements BusRemoteCallEventConsumer.ArtifactDelivery {
        private final CompletableFuture<RemoteCallOutcome> result;
        private final RemoteAgentCaller.EventObserver eventObserver;
        private final boolean streaming;
        private final RuntimeRdcClient.RouteCandidate route;
        private final String endpointUrl;
        private final String correlationId;
        private final String idempotencyKey;
        private String expectedResponseId;
        private final long deadline;
        private String taskId;
        private String streamReference;
        private int streamGeneration;
        private int streamReferenceRefreshes;
        private StreamHandle subscription;
        private boolean artifactDelivered;

        private PendingRemoteCall(CompletableFuture<RemoteCallOutcome> result,
                RemoteAgentCaller.EventObserver eventObserver, boolean streaming,
                RuntimeRdcClient.RouteCandidate route, String endpointUrl, String correlationId,
                String idempotencyKey, String requestId, long deadline) {
            this.result = result;
            this.eventObserver = eventObserver;
            this.streaming = streaming;
            this.route = route;
            this.endpointUrl = endpointUrl;
            this.correlationId = correlationId;
            this.idempotencyKey = idempotencyKey;
            this.expectedResponseId = requestId;
            this.deadline = deadline;
        }

        /** {@inheritDoc} */
        @Override
        public synchronized boolean hasDelivered() {
            return artifactDelivered;
        }

        /** {@inheritDoc} */
        @Override
        public synchronized void markDelivered() {
            artifactDelivered = true;
        }

        private synchronized void captureTaskId(String value) {
            if (value == null || value.isBlank() || value.equals(taskId)) {
                return;
            }
            taskId = value;
        }

        private synchronized void expectResponseId(String value) {
            expectedResponseId = value;
        }

        private synchronized String expectedResponseId() {
            return expectedResponseId;
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

        private synchronized RefreshDecision beginStreamReferenceRefresh(int generation) {
            if (generation != streamGeneration || result.isDone()) {
                return RefreshDecision.STALE;
            }
            streamGeneration++;
            closeSubscription();
            streamReference = null;
            if (streamReferenceRefreshes >= MAX_STREAM_REFERENCE_REFRESHES) {
                return RefreshDecision.EXHAUSTED;
            }
            streamReferenceRefreshes++;
            return RefreshDecision.REFRESH;
        }

        /**
         * Drops the local subscription without consuming a stream reference refresh.
         *
         * @param generation generation of the callback reporting the failure
         * @return true when this callback owned the current generation and dropped the stream
         */
        private synchronized boolean abandonStream(int generation) {
            if (generation != streamGeneration || result.isDone()) {
                return false;
            }
            streamGeneration++;
            closeSubscription();
            streamReference = null;
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
