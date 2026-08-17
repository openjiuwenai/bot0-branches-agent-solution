/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationEvent;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.ObservationTimeoutException;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.api.ErrorCodes;
import com.openjiuwen.client.tool.spi.LocalToolDescriptor;
import com.openjiuwen.client.tool.spi.ToolExecutionRecord;
import com.openjiuwen.client.tool.spi.ToolExposurePolicy;

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
import java.util.concurrent.ExecutionException;
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
    void runtimeBlockingNonTerminalResponseAutomaticallyPollsGetTask() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(runtimeTransport(baseUrl, Duration.ofSeconds(5), Duration.ofMillis(20)))
                .build()) {
            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .conversationId("strict-blocking")
                    .mode(InvocationMode.BLOCKING)
                    .input("return a working task")
                    .build());

            InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(TaskState.COMPLETED, snapshot.state());
            assertEquals(1, sendMessageCalls.get());
            assertEquals(1, getTaskCalls.get(), "Runtime BLOCKING must observe non-terminal Task");
            assertEquals(List.of("false"), returnImmediatelyValues);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayBlockingNonTerminalResponseAutomaticallyPollsGetTask() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(baseUrl, MAPPER, Duration.ofSeconds(5),
                        GatewayEndpointPolicy.INSTANCE, Duration.ofSeconds(5), Duration.ofMillis(20)))
                .build()) {
            InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder()
                            .conversationId("gateway-blocking")
                            .mode(InvocationMode.BLOCKING)
                            .input("return a working task")
                            .build())
                    .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(TaskState.COMPLETED, snapshot.state());
            assertEquals(1, sendMessageCalls.get());
            assertEquals(1, getTaskCalls.get(), "Gateway BLOCKING must observe a non-terminal Task");
            assertEquals(List.of("false"), returnImmediatelyValues);
            assertNull(snapshot.callTree());
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
    void runtimeAsyncWaitsForExplicitGetInvocation() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(runtimeTransport(baseUrl, Duration.ofSeconds(5), Duration.ofMillis(20)))
                .build()) {
            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .conversationId("async-create")
                    .mode(InvocationMode.ASYNC)
                    .input("return a working task")
                    .build());

            call.accepted().toCompletableFuture().get(3, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(100);

            assertEquals(1, sendMessageCalls.get());
            assertEquals(List.of("true"), returnImmediatelyValues);
            assertEquals(0, getTaskCalls.get(), "Runtime ASYNC must not start background GetTask");
            assertFalse(call.completion().toCompletableFuture().isDone());

            InvocationSnapshot queried = client.getInvocation(call.invocationRef())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            InvocationSnapshot completed = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(TaskState.COMPLETED, queried.state());
            assertEquals(TaskState.COMPLETED, completed.state());
            assertEquals(1, getTaskCalls.get());
            assertNull(completed.callTree());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayAsyncWaitsForExplicitGetInvocation() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(new A2aHttpTransportProvider(baseUrl, MAPPER, Duration.ofSeconds(5),
                        GatewayEndpointPolicy.INSTANCE, Duration.ofSeconds(5), Duration.ofMillis(20)))
                .build()) {
            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .conversationId("gateway-async")
                    .mode(InvocationMode.ASYNC)
                    .input("return a working task")
                    .build());

            call.accepted().toCompletableFuture().get(3, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(100);

            assertEquals(1, sendMessageCalls.get());
            assertEquals(List.of("true"), returnImmediatelyValues);
            assertEquals(0, getTaskCalls.get(), "Gateway ASYNC must not start background GetTask");
            assertFalse(call.completion().toCompletableFuture().isDone());

            InvocationSnapshot queried = client.getInvocation(call.invocationRef())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(TaskState.COMPLETED, queried.state());
            assertEquals(TaskState.COMPLETED,
                    call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS).state());
            assertEquals(1, getTaskCalls.get());
            assertNull(queried.callTree());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runtimeBlockingTimeoutCompletesExceptionallyAndCanBeQueried() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange -> handleAlwaysWorking(exchange,
                sendMessageCalls, getTaskCalls));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(runtimeTransport(baseUrl, Duration.ofMillis(120), Duration.ofMillis(20)))
                .build()) {
            InvocationCall call = client.invoke(InvocationRequest.builder()
                    .conversationId("runtime-timeout")
                    .mode(InvocationMode.BLOCKING)
                    .input("keep working")
                    .build());

            call.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
            ExecutionException thrown = org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> call.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            ObservationTimeoutException timeout = (ObservationTimeoutException) thrown.getCause();
            assertEquals(call.invocationRef(), timeout.invocationRef());
            assertEquals("task-working", timeout.diagnosticTaskRef());
            assertEquals(TaskState.WORKING, timeout.lastKnownState());
            assertEquals(1, sendMessageCalls.get());
            assertFalse(getTaskCalls.get() == 0);

            InvocationSnapshot later = client.getInvocation(call.invocationRef())
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(TaskState.WORKING, later.state());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void terminalTimeoutAndCloseReleaseActiveTransportMappings() throws Exception {
        AtomicInteger sendCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        HttpServer workingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        workingServer.createContext("/a2a", exchange -> handleAlwaysWorking(exchange, sendCalls, getCalls));
        workingServer.start();
        A2aHttpTransportProvider transport = runtimeTransport(
                "http://127.0.0.1:" + workingServer.getAddress().getPort(),
                Duration.ofMillis(100), Duration.ofMillis(20));
        try (AgentClient client = AgentClients.builder().transport(transport).build()) {
            InvocationCall timedOut = client.invoke(InvocationRequest.builder().conversationId("release-timeout")
                    .mode(InvocationMode.BLOCKING).input("working").build());
            org.junit.jupiter.api.Assertions.assertThrows(ExecutionException.class,
                    () -> timedOut.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(0, transport.activeInvocationCount());
            assertEquals(0, transport.activeTaskCount());

            InvocationCall closed = client.invoke(InvocationRequest.builder().conversationId("release-close")
                    .mode(InvocationMode.ASYNC).input("working").build());
            closed.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
            closed.close();
            assertEquals(0, transport.activeInvocationCount());
            assertEquals(0, transport.activeTaskCount());
        } finally {
            workingServer.stop(0);
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
    void runtimeAsyncContinueInputWaitsForExplicitGetInvocation() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        List<String> returnImmediatelyValues = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange ->
                handle(exchange, sendMessageCalls, getTaskCalls, returnImmediatelyValues));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(runtimeTransport(baseUrl, Duration.ofSeconds(5), Duration.ofMillis(20)))
                .build()) {
            InvocationCall initial = client.invoke(InvocationRequest.builder()
                    .conversationId("async-input")
                    .mode(InvocationMode.ASYNC)
                    .input("need user input")
                    .build());
            initial.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            InvocationCall resumed = client.continueInput(ContinueInputRequest.builder()
                    .conversationId("async-input")
                    .relatedInvocationRef(initial.invocationRef())
                    .input("user answer")
                    .build());
            resumed.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(100);

            assertEquals(2, sendMessageCalls.get());
            assertEquals(0, getTaskCalls.get(), "ASYNC continuation must not start background GetTask");
            assertFalse(resumed.completion().toCompletableFuture().isDone());

            InvocationSnapshot queried = client.getInvocation(resumed.invocationRef())
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);
            InvocationSnapshot completed = resumed.completion().toCompletableFuture()
                    .get(3, TimeUnit.SECONDS);

            assertEquals(TaskState.COMPLETED, queried.state());
            assertEquals(TaskState.COMPLETED, completed.state());
            assertEquals(2, sendMessageCalls.get());
            assertEquals(1, getTaskCalls.get());
            assertEquals(List.of("true", "true"), returnImmediatelyValues);
            assertNull(completed.callTree());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runtimeAsyncContinueInputHasNoBackgroundObservationTimeout() throws Exception {
        AtomicInteger sendMessageCalls = new AtomicInteger();
        AtomicInteger getTaskCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange -> handleInputThenAlwaysWorking(exchange,
                sendMessageCalls, getTaskCalls));
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder()
                .transport(runtimeTransport(baseUrl, Duration.ofMillis(120), Duration.ofMillis(20)))
                .build()) {
            InvocationCall initial = client.invoke(InvocationRequest.builder()
                    .conversationId("resume-timeout")
                    .mode(InvocationMode.ASYNC)
                    .input("need user input")
                    .build());
            initial.completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

            InvocationCall resumed = client.continueInput(ContinueInputRequest.builder()
                    .conversationId("resume-timeout")
                    .relatedInvocationRef(initial.invocationRef())
                    .input("keep working")
                    .build());
            resumed.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
            TimeUnit.MILLISECONDS.sleep(250);

            assertEquals(2, sendMessageCalls.get());
            assertEquals(0, getTaskCalls.get());
            assertFalse(resumed.completion().toCompletableFuture().isDone(),
                    "ASYNC completion stays pending until an explicit query reaches a settlement point");

            InvocationSnapshot working = client.getInvocation(resumed.invocationRef())
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(TaskState.WORKING, working.state());
            assertEquals(1, getTaskCalls.get());
            assertFalse(resumed.completion().toCompletableFuture().isDone());
            resumed.close();
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

    @Test
    void blockingCompletedTaskUsesArtifactsAsOutputText() throws Exception {
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
                    .conversationId("artifact-output")
                    .mode(InvocationMode.BLOCKING)
                    .input("completed with artifacts")
                    .build());

            InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals(TaskState.COMPLETED, snapshot.state());
            assertEquals("target runtime received: hello[trace=trace-11][agent=demo-a2a-agent-a]",
                    snapshot.outputText());
            assertEquals(1, sendMessageCalls.get());
            assertEquals(0, getTaskCalls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void incompleteClientToolInterruptIsDiagnosedAndNeverExecuted() throws Exception {
        AtomicInteger executions = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", A2aHttpTransportProviderTest::handleIncompleteClientTool);
        server.start();

        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        try (AgentClient client = AgentClients.builder().endpointType(com.openjiuwen.client.api.EndpointType.RUNTIME)
                .endpointUrl(baseUrl).build()) {
            client.tools().register(LocalToolDescriptor.builder("local.echo").displayName("echo")
                    .description("echo").build(), (invocation, context) -> {
                        executions.incrementAndGet();
                        return ToolExecutionRecord.ok(invocation.toolCallId(), java.util.Map.of());
                    });
            client.exposeInConversation("incomplete-tool", ToolExposurePolicy.allow("local.echo"));
            InvocationCall call = client.invoke(InvocationRequest.builder().conversationId("incomplete-tool")
                    .mode(InvocationMode.BLOCKING).input("hello").build());
            List<InvocationEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch diagnosed = new CountDownLatch(1);
            call.events().subscribe(collectingSubscriber(events, diagnosed));

            InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
            diagnosed.await(1, TimeUnit.SECONDS);

            assertEquals(0, executions.get());
            assertEquals(TaskState.INPUT_REQUIRED, snapshot.state());
            assertEquals(null, snapshot.pendingToolCall());
            org.junit.jupiter.api.Assertions.assertTrue(events.stream().anyMatch(event ->
                    event instanceof InvocationEvent.ProtocolDiagnostic diagnostic
                            && ErrorCodes.INPUT_RESUME_TARGET_MISSING.equals(diagnostic.code())));
        } finally {
            server.stop(0);
        }
    }

    private static Flow.Subscriber<InvocationEvent> collectingSubscriber(List<InvocationEvent> events,
            CountDownLatch diagnosed) {
        return new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(InvocationEvent item) {
                events.add(item);
                if (item instanceof InvocationEvent.ProtocolDiagnostic) {
                    diagnosed.countDown();
                }
            }

            @Override
            public void onError(Throwable throwable) {
            }

            @Override
            public void onComplete() {
            }
        };
    }

    private static void handleIncompleteClientTool(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"tool\",\"result\":{\"task\":{"
                + "\"id\":\"task-tool\",\"contextId\":\"incomplete-tool\","
                + "\"status\":{\"state\":\"TASK_STATE_INPUT_REQUIRED\",\"message\":{"
                + "\"metadata\":{\"_interrupt\":{\"context\":{\"_interrupt_kind\":\"client_tool\"}}}}}}}}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
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
            boolean completedWithArtifacts = "completed with artifacts".equals(input);
            String state = completedWithArtifacts ? "TASK_STATE_COMPLETED" : !taskId.isBlank()
                    ? (returnNow ? "TASK_STATE_WORKING" : "TASK_STATE_COMPLETED")
                    : ("need user input".equals(input) ? "TASK_STATE_INPUT_REQUIRED" : "TASK_STATE_WORKING");
            String contextId = message.path("contextId").asText("strict-blocking-input");
            String artifacts = completedWithArtifacts
                    ? ",\"artifacts\":[{\"artifactId\":\"a-1\",\"parts\":["
                            + "{\"text\":\"target runtime received: hello\"},"
                            + "{\"data\":{\"content\":\"[trace=trace-11][agent=demo-a2a-agent-a]\"}},"
                            + "{\"data\":{\"text\":\"not-output\",\"message\":\"not-output\"}}]}]"
                    : "";
            body = "{\"jsonrpc\":\"2.0\",\"id\":\"create\",\"result\":{\"task\":{"
                    + "\"id\":\"task-working\",\"contextId\":\"" + contextId + "\","
                    + "\"status\":{\"state\":\"" + state + "\"}" + artifacts + "}}}";
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

    private static A2aHttpTransportProvider runtimeTransport(String baseUrl, Duration observationTimeout,
            Duration pollInterval) {
        return new A2aHttpTransportProvider(baseUrl, MAPPER, Duration.ofSeconds(5),
                RuntimeEndpointPolicy.INSTANCE, observationTimeout, pollInterval);
    }

    private static void handleAlwaysWorking(HttpExchange exchange, AtomicInteger sendMessageCalls,
            AtomicInteger getTaskCalls) throws IOException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        if ("SendMessage".equals(method)) {
            sendMessageCalls.incrementAndGet();
        } else if ("GetTask".equals(method)) {
            getTaskCalls.incrementAndGet();
        } else {
            // Unknown method; no-op — the mock only records the two known A2A methods.
        }
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"working\",\"result\":{\"task\":{"
                + "\"id\":\"task-working\",\"contextId\":\"runtime-timeout\","
                + "\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void handleInputThenAlwaysWorking(HttpExchange exchange, AtomicInteger sendMessageCalls,
            AtomicInteger getTaskCalls) throws IOException {
        JsonNode request = MAPPER.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        String state;
        if ("SendMessage".equals(method)) {
            int call = sendMessageCalls.incrementAndGet();
            state = call == 1 ? "TASK_STATE_INPUT_REQUIRED" : "TASK_STATE_WORKING";
        } else {
            getTaskCalls.incrementAndGet();
            state = "TASK_STATE_WORKING";
        }
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"resume\",\"result\":{\"task\":{"
                + "\"id\":\"task-resume-timeout\",\"contextId\":\"resume-timeout\","
                + "\"status\":{\"state\":\"" + state + "\"}}}}";
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
