/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A2A HTTP transport provider 的单元测试，验证 BLOCKING/ASYNC/STREAMING 模式下的创建、续跑与恢复行为。
 *
 * @since 2026-07-27
 */
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
    void continueInputInheritsInitialMode() throws Exception {
        // FEAT-006 §47：续轮 mode 强制继承首轮 invocation 的 mode，业务在 continueInput 中声明的 mode 被忽略。
        // 首轮 BLOCKING → 续轮仍 BLOCKING（unary SendMessage, returnImmediately=false），即使业务传 ASYNC 也不生效。
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
                    .conversationId("inherit-blocking")
                    .mode(InvocationMode.BLOCKING)
                    .input("need user input")
                    .build());
            initial.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            // 业务显式传 ASYNC，但续轮应继承首轮 BLOCKING → returnImmediately=false
            InvocationCall resumed = client.continueInput(ContinueInputRequest.builder()
                    .conversationId("inherit-blocking")
                    .relatedInvocationRef(initial.invocationRef())
                    .mode(InvocationMode.ASYNC)
                    .input("user answer")
                    .build());
            InvocationSnapshot completed = resumed.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(TaskState.COMPLETED, completed.state());
            assertEquals(2, sendMessageCalls.get());
            assertEquals(0, getTaskCalls.get());
            // 续轮继承 BLOCKING：returnImmediately=false（而非业务声明的 ASYNC=true）
            assertEquals(List.of("false", "false"), returnImmediatelyValues);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void continueInputStreamingResumeInheritsStreamingMode() throws Exception {
        // FEAT-006 §47：首轮 STREAMING → 续轮继承 STREAMING，走 SendStreamingMessage（SSE）而非 unary SendMessage。
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger streamingResumeCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handleStreamingResume(exchange, sendMessageCalls, streamingResumeCalls, getTaskCalls));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(baseUrl, MAPPER, Duration.ofSeconds(5)))
                .build()) {
            InvocationCall initial = client.invoke(InvocationRequest.builder()
                    .conversationId("inherit-streaming")
                    .mode(InvocationMode.STREAMING)
                    .input("need user input")
                    .build());

            AtomicReference<InvocationCall> continuation = new AtomicReference<>();
            CountDownLatch prompted = new CountDownLatch(1);
            initial.events().subscribe(streamingPromptSubscriber(client, initial, continuation, prompted));
            prompted.await(5, TimeUnit.SECONDS);
            InvocationCall resumed = continuation.get();
            assertNotNull(resumed, "continueInput issued after INPUT_REQUIRED prompt");

            InvocationSnapshot completed = resumed.completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(TaskState.COMPLETED, completed.state());
            assertEquals(1, streamingResumeCalls.get());
            assertEquals(0, sendMessageCalls.get(), "STREAMING resume must not use unary SendMessage");
            assertEquals(0, getTaskCalls.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 创建用于 STREAMING + INPUT_REQUIRED 场景的订阅者：收到 InputRequired 后发起 continueInput。
     *
     * @param client Agent 客户端
     * @param initial 首轮调用
     * @param continuation 续跑调用容器
     * @param prompted 倒计时锁
     * @return 订阅者
     */
    private static Flow.Subscriber<InvocationEvent> streamingPromptSubscriber(
            AgentClient client, InvocationCall initial,
            AtomicReference<InvocationCall> continuation, CountDownLatch prompted) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent event) {
                if (event instanceof InvocationEvent.InputRequired ir && ir.toolCall() == null
                        && continuation.get() == null) {
                    continuation.set(client.continueInput(ContinueInputRequest.builder()
                            .conversationId("inherit-streaming")
                            .relatedInvocationRef(initial.invocationRef())
                            .input("user answer")
                            .build()));
                    prompted.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                prompted.countDown();
            }

            @Override
            public void onComplete() {
                prompted.countDown();
            }
        };
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

    /**
     * Mock server handler for STREAMING resume scenarios: supports SendStreamingMessage create
     * (returns SSE with INPUT_REQUIRED) and SendStreamingMessage resume (returns SSE with COMPLETED).
     *
     * @param exchange HTTP 交换对象
     * @param sendMessageCalls unary SendMessage 调用计数
     * @param streamingResumeCalls 流式续跑调用计数
     * @param getTaskCalls GetTask 调用计数
     * @throws IOException 写响应失败时抛出
     */
    private static void handleStreamingResume(HttpExchange exchange, AtomicInteger sendMessageCalls,
            AtomicInteger streamingResumeCalls, AtomicInteger getTaskCalls) throws IOException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        JsonNode message = request.path("params").path("message");
        String taskId = message.path("taskId").asText("");
        String contextId = message.path("contextId").asText("inherit-streaming");

        if ("SendStreamingMessage".equals(method)) {
            if (taskId.isBlank()) {
                // 创建：返回 SSE 流，首帧 INPUT_REQUIRED（模拟等待用户输入）。
                String frame = "{\"jsonrpc\":\"2.0\",\"result\":{\"task\":{\"id\":\"task-streaming\",\"contextId\":\""
                        + contextId + "\",\"status\":{\"state\":\"TASK_STATE_INPUT_REQUIRED\"}}}}";
                writeSseResponse(exchange, frame);
            } else {
                // 流式续跑：返回 SSE 流，首帧 COMPLETED。
                streamingResumeCalls.incrementAndGet();
                String frame = "{\"jsonrpc\":\"2.0\",\"result\":{\"task\":{\"id\":\"task-streaming\",\"contextId\":\""
                        + contextId + "\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\","
                        + "\"message\":{\"parts\":[{\"text\":\"done\"}]}}}}}";
                writeSseResponse(exchange, frame);
            }
        } else if ("SendMessage".equals(method)) {
            sendMessageCalls.incrementAndGet();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":\"unary\",\"result\":{\"task\":{"
                    + "\"id\":\"task-streaming\",\"contextId\":\"" + contextId + "\","
                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } else if ("GetTask".equals(method)) {
            getTaskCalls.incrementAndGet();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":\"get\",\"result\":{\"task\":{"
                    + "\"id\":\"task-streaming\",\"contextId\":\"" + contextId + "\","
                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}}}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } else {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32601,"
                    + "\"message\":\"method not found\"}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }
    }

    /**
     * Write a single SSE frame and close the stream (simulates a short SSE response).
     *
     * @param exchange HTTP 交换对象
     * @param frame JSON 帧内容
     * @throws IOException 写响应失败时抛出
     */
    private static void writeSseResponse(HttpExchange exchange, String frame) throws IOException {
        byte[] payload = ("event: jsonrpc\ndata: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().flush();
        exchange.close();
    }
}
