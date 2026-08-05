/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.AgentBusRequestSubmitter;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.client.ClientEvent;
import org.a2aproject.sdk.client.TaskEvent;
import org.a2aproject.sdk.client.TaskUpdateEvent;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Tests the non-streaming Runtime-to-Runtime Agent Bus caller path.
 *
 * @since 2026-08-04
 */
class AgentBusRemoteAgentCallerTest {
    private HttpServer server;
    private List<ForwardingEnvelope> submitted;
    private AgentBusRequestSubmitter requestSubmitter;
    private AgentBusRemoteAgentCaller caller;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/registry/instances/tenant-a/agent-b", exchange -> respond(exchange, 200,
                "[{\"serviceId\":\"runtime-b\",\"routeHandle\":\"route-b\"}]"));
        server.createContext("/api/registry/route-handle/resolve", exchange -> respond(exchange, 200,
                "{\"endpointUrl\":\"http://runtime-b:8080\",\"instanceId\":\"runtime-b-1\"}"));
        server.start();
        RuntimeRdcClient registry = new RuntimeRdcClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        submitted = new ArrayList<>();
        requestSubmitter = envelope -> {
            submitted.add(envelope);
            return ForwardingReceipt.accepted(envelope.messageId(), envelope.tenantId(),
                    System.currentTimeMillis());
        };
        caller = new AgentBusRemoteAgentCaller(registry, requestSubmitter, Runnable::run,
                "tenant-a", "runtime-a", 30_000L);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void enqueuesA2aRequestAndCompletesFromTerminalProjection() throws Exception {
        AtomicReference<String> remoteTaskId = new AtomicReference<>();
        var future = caller.callOutcome(new RemoteCall("agent-b", "hello", "context-a", null, Map.of()),
                null, remoteTaskId::set);

        assertThat(submitted).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo(AgentBusEventType.A2A_CALL_REQUESTED);
            assertThat(record.targetServiceId()).isEqualTo("runtime-b");
            assertThat(record.routeHandle().value()).isEqualTo("route-b");
            assertThat(record.originalCaller()).isEqualTo("runtime-a");
            assertThat(record.inlinePayload()).contains("\"method\":\"SendMessage\"")
                    .contains("\"messageId\":\"" + record.messageId().value() + "\"")
                    .contains("\"contextId\":\"context-a\"");
        });

        ForwardingEnvelope request = submitted.get(0);
        assertThat(caller.accept(response(request, AgentBusEventType.A2A_CALL_ACCEPTED,
                "taskId=task-b;projectionKind=ACCEPTED;revision=0"))).isTrue();
        assertThat(remoteTaskId.get()).isEqualTo("task-b");

        Task completed = Task.builder().id("task-b").contextId("context-a")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
        String json = JsonUtil.toJson(completed);
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        caller.accept(response(request, AgentBusEventType.A2A_CALL_TERMINAL,
                "taskId=task-b;projectionKind=TERMINAL;revision=1;status=completed;"
                        + "a2aResponseType=Task;a2aResponse=" + encoded));

        assertThat(future.join().resultCategory()).isEqualTo("COMPLETED");
        assertThat(future.join().remoteTaskId()).isEqualTo("task-b");
        assertThat(caller.pendingCount()).isZero();
        assertThat(caller.accept(response(request, AgentBusEventType.A2A_CALL_TERMINAL,
                "taskId=task-b;projectionKind=TERMINAL;revision=1;status=completed"))).isFalse();
    }

    @Test
    void mapsRejectedProjectionToRemoteRejectedOutcome() throws Exception {
        var future = caller.callOutcome(new RemoteCall("agent-b", "hello", "context-a", null, Map.of()),
                null, null);
        ForwardingEnvelope request = submittedSingle();

        assertThat(caller.accept(response(request, AgentBusEventType.A2A_CALL_REJECTED,
                "taskId=task-b;projectionKind=REJECTED;revision=0;reason=denied"))).isTrue();

        assertThat(future.join().resultCategory()).isEqualTo("REMOTE_REJECTED");
        assertThat(future.join().remoteTaskId()).isEqualTo("task-b");
        assertThat(caller.pendingCount()).isZero();
    }

    @Test
    void removesPendingCallAfterResponseTimeout() throws Exception {
        RuntimeRdcClient registry = new RuntimeRdcClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        caller = new AgentBusRemoteAgentCaller(registry, requestSubmitter, Runnable::run,
                "tenant-a", "runtime-a", 20L);

        var future = caller.callOutcome(new RemoteCall("agent-b", "hello", "context-a", null, Map.of()),
                null, null);

        assertThatThrownBy(future::join).hasCauseInstanceOf(TimeoutException.class);
        assertThat(caller.pendingCount()).isZero();
    }

    @Test
    void opensSseAfterStreamReadyAndForwardsChunks() throws Exception {
        AtomicReference<com.openjiuwen.service.app.controller.a2a.client.A2ATaskSubscriptionClient
                .TaskSubscriptionRequest> subscriptionRequest = new AtomicReference<>();
        AtomicReference<Consumer<ClientEvent>> streamEvents = new AtomicReference<>();
        AtomicBoolean subscriptionClosed = new AtomicBoolean();
        RuntimeRdcClient registry = new RuntimeRdcClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        caller = new AgentBusRemoteAgentCaller(registry, requestSubmitter, Runnable::run,
                "tenant-a", "runtime-a", 30_000L, (request, events, completion, error) -> {
                    subscriptionRequest.set(request);
                    streamEvents.set(events);
                    return () -> subscriptionClosed.set(true);
                });
        List<QueryChunk> chunks = new ArrayList<>();
        QueryStreamObserver observer = observer(chunks);

        var future = caller.callOutcome(new RemoteCall("agent-b", "hello", "context-a", null,
                Map.of(), Map.of(), true), observer, null);
        ForwardingEnvelope request = submittedSingle();
        assertThat(request.inlinePayload()).contains("\"method\":\"SendStreamingMessage\"");
        caller.accept(response(request, AgentBusEventType.A2A_CALL_ACCEPTED,
                "taskId=task-stream;projectionKind=ACCEPTED;revision=0"));
        caller.accept(response(request, AgentBusEventType.A2A_STREAM_READY,
                "taskId=task-stream;streamRef=ref-1;projectionKind=STREAM_READY;revision=0"));

        assertThat(subscriptionRequest.get().endpointUrl()).isEqualTo("http://runtime-b:8080");
        assertThat(subscriptionRequest.get().taskId()).isEqualTo("task-stream");
        assertThat(subscriptionRequest.get().streamReference()).isEqualTo("ref-1");
        streamEvents.get().accept(artifactEvent("task-stream", "partial"));
        streamEvents.get().accept(new TaskEvent(task("task-stream", TaskState.TASK_STATE_COMPLETED)));

        assertThat(chunks).singleElement().extracting(QueryChunk::getData).isEqualTo("partial");
        assertThat(future.join().resultCategory()).isEqualTo("COMPLETED");
        assertThat(subscriptionClosed).isTrue();
        assertThat(caller.pendingCount()).isZero();
    }

    @Test
    void refreshesStreamReferenceOnceAfterSubscriptionFailure() throws Exception {
        List<Consumer<ClientEvent>> streamEvents = new ArrayList<>();
        List<Consumer<Throwable>> streamErrors = new ArrayList<>();
        RuntimeRdcClient registry = new RuntimeRdcClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        caller = new AgentBusRemoteAgentCaller(registry, requestSubmitter, Runnable::run,
                "tenant-a", "runtime-a", 30_000L, (request, events, completion, error) -> {
                    streamEvents.add(events);
                    streamErrors.add(error);
                    return () -> { };
                });

        var future = caller.callOutcome(new RemoteCall("agent-b", "hello", "context-a", null,
                Map.of(), Map.of(), true), observer(new ArrayList<>()), null);
        ForwardingEnvelope create = submittedSingle();
        caller.accept(response(create, AgentBusEventType.A2A_CALL_ACCEPTED,
                "taskId=task-stream;projectionKind=ACCEPTED;revision=0"));
        caller.accept(response(create, AgentBusEventType.A2A_STREAM_READY,
                "taskId=task-stream;streamRef=expired;projectionKind=STREAM_READY;revision=0"));
        streamErrors.get(0).accept(new IllegalStateException("expired stream reference"));

        assertThat(submitted).hasSize(2);
        ForwardingEnvelope refresh = submitted.stream()
                .filter(record -> record.eventType() == AgentBusEventType.A2A_STREAM_SUBSCRIBE_REQUESTED)
                .findFirst().orElseThrow();
        assertThat(refresh.inlinePayload()).contains("\"method\":\"SubscribeToTask\"")
                .contains("\"id\":\"task-stream\"");
        assertThat(refresh.correlationId()).isEqualTo(create.correlationId());

        caller.accept(response(refresh, AgentBusEventType.A2A_STREAM_READY,
                "taskId=task-stream;streamRef=fresh;projectionKind=STREAM_READY;revision=0"));
        assertThat(streamEvents).hasSize(2);
        streamEvents.get(1).accept(new TaskEvent(task("task-stream", TaskState.TASK_STATE_COMPLETED)));
        assertThat(future.join().resultCategory()).isEqualTo("COMPLETED");
    }

    @Test
    void cancellingFutureClosesLocalSseSubscription() throws Exception {
        AtomicBoolean subscriptionClosed = new AtomicBoolean();
        RuntimeRdcClient registry = new RuntimeRdcClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        caller = new AgentBusRemoteAgentCaller(registry, requestSubmitter, Runnable::run,
                "tenant-a", "runtime-a", 30_000L, (request, events, completion, error) ->
                        () -> subscriptionClosed.set(true));

        var future = caller.callOutcome(new RemoteCall("agent-b", "hello", "context-a", null,
                Map.of(), Map.of(), true), observer(new ArrayList<>()), null);
        ForwardingEnvelope request = submittedSingle();
        caller.accept(response(request, AgentBusEventType.A2A_CALL_ACCEPTED,
                "taskId=task-stream;projectionKind=ACCEPTED;revision=0"));
        caller.accept(response(request, AgentBusEventType.A2A_STREAM_READY,
                "taskId=task-stream;streamRef=ref-1;projectionKind=STREAM_READY;revision=0"));

        assertThat(future.cancel(true)).isTrue();
        assertThat(subscriptionClosed).isTrue();
        assertThat(caller.pendingCount()).isZero();
    }

    private ForwardingEnvelope submittedSingle() {
        assertThat(submitted).hasSize(1);
        return submitted.get(0);
    }

    private static TaskUpdateEvent artifactEvent(String taskId, String text) {
        Task working = task(taskId, TaskState.TASK_STATE_WORKING);
        Artifact artifact = Artifact.builder().artifactId("artifact-1").parts(new TextPart(text)).build();
        TaskArtifactUpdateEvent update = TaskArtifactUpdateEvent.builder().taskId(taskId).contextId("context-a")
                .artifact(artifact).append(true).lastChunk(false).build();
        return new TaskUpdateEvent(working, update);
    }

    private static Task task(String taskId, TaskState state) {
        return Task.builder().id(taskId).contextId("context-a").status(new TaskStatus(state)).build();
    }

    private static QueryStreamObserver observer(List<QueryChunk> chunks) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    private static BrokerInboundMessage response(ForwardingEnvelope request, AgentBusEventType type,
            String inlinePayload) {
        return new BrokerInboundMessage("tenant-a", "response-" + type, "runtime-b", "runtime-a",
                "runtime-caller-runtime-a", null, request.correlationId(), type, request.traceId(),
                request.idempotencyKey(), request.routeHandle().value(), "agent-runtime-response",
                request.deadlineMillisEpoch(), inlinePayload, null);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
