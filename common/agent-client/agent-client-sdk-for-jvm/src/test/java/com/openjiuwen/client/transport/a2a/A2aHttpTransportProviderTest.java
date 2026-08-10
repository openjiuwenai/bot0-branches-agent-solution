/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.TaskState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class A2aHttpTransportProviderTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void blockingNonTerminalResponseDoesNotPollGetTask() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(baseUrl, MAPPER, Duration.ofSeconds(5)))
                .build()) {
            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .conversationId("strict-blocking")
                    .mode(InvocationMode.BLOCKING)
                    .input("return a working task")
                    .build());

            InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(TaskState.WORKING, snapshot.state());
            assertFalse(snapshot.terminal());
            assertNotNull(snapshot.recovery());
            assertEquals(1, sendMessageCalls.get());
            assertEquals(0, getTaskCalls.get(), "strict BLOCKING must not poll GetTask");
            assertEquals(List.of("false"), returnImmediatelyValues);

            InvocationSnapshot observed = client.getInvocation(call.invocationRef())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(TaskState.COMPLETED, observed.state());
            assertEquals(1, getTaskCalls.get(), "explicit getInvocation must issue exactly one GetTask");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void blockingUserInputRequiredSettlesAndCanContinue() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(baseUrl, MAPPER, Duration.ofSeconds(5)))
                .build()) {
            InvocationCall initial = client.invoke(InvocationRequest.builder()
                    .conversationId("strict-blocking-input")
                    .mode(InvocationMode.BLOCKING)
                    .input("need user input")
                    .build());

            InvocationSnapshot waiting = initial.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(TaskState.INPUT_REQUIRED, waiting.state());
            assertFalse(waiting.terminal());

            InvocationCall resumed = client.continueInput(ContinueInputRequest.builder()
                    .conversationId("strict-blocking-input")
                    .relatedInvocationRef(initial.invocationRef())
                    .mode(InvocationMode.BLOCKING)
                    .input("user answer")
                    .build());
            InvocationSnapshot completed = resumed.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(TaskState.COMPLETED, completed.state());
            assertEquals(2, sendMessageCalls.get());
            assertEquals(0, getTaskCalls.get());
            assertEquals(List.of("false", "false"), returnImmediatelyValues);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void asyncCreateRequestsImmediateReturn() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(baseUrl, MAPPER, Duration.ofSeconds(5)))
                .build()) {
            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .conversationId("async-create")
                    .mode(InvocationMode.ASYNC)
                    .input("return a working task")
                    .build());

            call.accepted().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(1, sendMessageCalls.get());
            assertEquals(0, getTaskCalls.get());
            assertEquals(List.of("true"), returnImmediatelyValues);
            assertFalse(call.completion().toCompletableFuture().isDone());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void continueInputUsesRequestedAsyncMode() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(baseUrl, MAPPER, Duration.ofSeconds(5)))
                .build()) {
            InvocationCall initial = client.invoke(InvocationRequest.builder()
                    .conversationId("async-continue")
                    .mode(InvocationMode.BLOCKING)
                    .input("need user input")
                    .build());
            initial.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            InvocationCall resumed = client.continueInput(ContinueInputRequest.builder()
                    .conversationId("async-continue")
                    .relatedInvocationRef(initial.invocationRef())
                    .mode(InvocationMode.ASYNC)
                    .input("user answer")
                    .build());
            var accepted = resumed.accepted().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(2, sendMessageCalls.get());
            assertEquals(0, getTaskCalls.get());
            assertEquals(List.of("false", "true"), returnImmediatelyValues);
            assertEquals("task-working", accepted.diagnosticTaskRef());
            assertFalse(resumed.completion().toCompletableFuture().isDone());

            InvocationSnapshot observed = client.getInvocation(resumed.invocationRef())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(TaskState.COMPLETED, observed.state());
            assertEquals(1, getTaskCalls.get());
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, AtomicInteger sendMessageCalls,
            AtomicInteger getTaskCalls, List<String> returnImmediatelyValues) throws IOException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        String body;
        if ("SendMessage".equals(method)) {
            sendMessageCalls.incrementAndGet();
            JsonNode returnImmediately = request.path("params").path("configuration").get("returnImmediately");
            returnImmediatelyValues.add(returnImmediately != null && returnImmediately.isBoolean()
                    ? Boolean.toString(returnImmediately.asBoolean()) : "<missing>");
            boolean returnNow = returnImmediately != null && returnImmediately.asBoolean(false);
            JsonNode message = request.path("params").path("message");
            String taskId = message.path("taskId").asText("");
            String input = message.path("parts").path(0).path("text").asText("");
            String state = !taskId.isBlank()
                    ? (returnNow ? "TASK_STATE_WORKING" : "TASK_STATE_COMPLETED")
                    : ("need user input".equals(input) ? "TASK_STATE_INPUT_REQUIRED" : "TASK_STATE_WORKING");
            String contextId = message.path("contextId").asText("strict-blocking-input");
            body = "{\"jsonrpc\":\"2.0\",\"id\":\"create\",\"result\":{\"task\":{"
                    + "\"id\":\"task-working\",\"contextId\":\"" + contextId + "\","
                    + "\"status\":{\"state\":\"" + state + "\"}}}}";
        } else if ("GetTask".equals(method)) {
            getTaskCalls.incrementAndGet();
            body = "{\"jsonrpc\":\"2.0\",\"id\":\"get\",\"result\":{\"task\":{"
                    + "\"id\":\"task-working\",\"contextId\":\"strict-blocking\","
                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}}";
        } else {
            body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32601,"
                    + "\"message\":\"method not found\"}}";
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
