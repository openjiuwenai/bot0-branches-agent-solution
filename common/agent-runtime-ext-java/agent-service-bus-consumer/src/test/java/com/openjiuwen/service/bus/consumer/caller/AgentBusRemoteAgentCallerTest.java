/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.test.InMemoryForwardingOutbox;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests the non-streaming Runtime-to-Runtime Agent Bus caller path.
 *
 * @since 2026-08-04
 */
class AgentBusRemoteAgentCallerTest {
    private HttpServer server;
    private InMemoryForwardingOutbox outbox;
    private AgentBusRemoteAgentCaller caller;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/registry/instances/tenant-a/agent-b", exchange -> respond(exchange, 200,
                "[{\"serviceId\":\"runtime-b\",\"routeHandle\":\"route-b\"}]"));
        server.start();
        RuntimeRdcClient registry = new RuntimeRdcClient(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        outbox = new InMemoryForwardingOutbox();
        caller = new AgentBusRemoteAgentCaller(registry, outbox, Runnable::run,
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

        List<ForwardingOutboxRecord> records = outbox.claimDue("tenant-a", System.currentTimeMillis(), 1,
                "runtime-a", System.currentTimeMillis() + 30_000L);
        assertThat(records).singleElement().satisfies(record -> {
            assertThat(record.eventType()).isEqualTo(AgentBusEventType.A2A_CALL_REQUESTED);
            assertThat(record.targetServiceId()).isEqualTo("runtime-b");
            assertThat(record.routeHandle().value()).isEqualTo("route-b");
            assertThat(record.originalCaller()).isEqualTo("runtime-a");
            assertThat(record.inlinePayload()).contains("\"method\":\"SendMessage\"")
                    .contains("\"messageId\":\"" + record.messageId().value() + "\"")
                    .contains("\"contextId\":\"context-a\"");
        });

        ForwardingOutboxRecord request = records.get(0);
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
    }

    private static BrokerInboundMessage response(ForwardingOutboxRecord request, AgentBusEventType type,
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
