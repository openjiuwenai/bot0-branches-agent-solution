/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.ContinueInputRequest;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.ObservationTimeoutException;
import com.openjiuwen.client.api.TaskState;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Blocking and async A2A transport tests.
 *
 * @since 2026-08-27
 */
class A2aHttpBlockingAsyncTest {
    @Test
    void runtimeBlockingPollsGetTask() throws Exception {
        AtomicInteger sendCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        List<String> returnImmediately = new CopyOnWriteArrayList<>();
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) -> {
            String method = request.path("method").asText();
            if ("SendMessage".equals(method)) {
                sendCalls.incrementAndGet();
                returnImmediately.add(Boolean.toString(request.path("params").path("configuration")
                        .path("returnImmediately").asBoolean(false)));
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking", "TASK_STATE_WORKING"));
                return;
            } else if ("GetTask".equals(method)) {
                getCalls.incrementAndGet();
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking", "TASK_STATE_COMPLETED"));
                return;
            } else {
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking", "TASK_STATE_COMPLETED"));
            }
        })) {
            try (AgentClient client = AgentClients.builder()
                    .transport(new A2aHttpTransportProvider(server.baseUrl(), A2aHttpTestSupport.MAPPER,
                            Duration.ofSeconds(5), RuntimeEndpointPolicy.INSTANCE,
                            Duration.ofSeconds(5), Duration.ofMillis(20)))
                    .build()) {
                InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder()
                                .conversationId("strict-blocking")
                                .mode(InvocationMode.BLOCKING)
                                .input("return a working task")
                                .build())
                        .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(TaskState.COMPLETED, snapshot.state());
                assertEquals(1, sendCalls.get());
                assertEquals(1, getCalls.get());
                assertEquals(List.of("false"), returnImmediately);
            }
        }
    }

    @Test
    void gatewayBlockingKeepsNoCallTree() throws Exception {
        AtomicInteger sendCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        List<String> returnImmediately = new CopyOnWriteArrayList<>();
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) -> {
            String method = request.path("method").asText();
            if ("SendMessage".equals(method)) {
                sendCalls.incrementAndGet();
                returnImmediately.add(Boolean.toString(request.path("params").path("configuration")
                        .path("returnImmediately").asBoolean(false)));
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking", "TASK_STATE_WORKING"));
                return;
            } else if ("GetTask".equals(method)) {
                getCalls.incrementAndGet();
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking", "TASK_STATE_COMPLETED"));
                return;
            } else {
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking", "TASK_STATE_COMPLETED"));
            }
        })) {
            try (AgentClient client = AgentClients.builder()
                    .transport(new A2aHttpTransportProvider(server.baseUrl(), A2aHttpTestSupport.MAPPER,
                            Duration.ofSeconds(5), GatewayEndpointPolicy.INSTANCE,
                            Duration.ofSeconds(5), Duration.ofMillis(20)))
                    .build()) {
                InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder()
                                .conversationId("gateway-blocking")
                                .mode(InvocationMode.BLOCKING)
                                .input("return a working task")
                                .build())
                        .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(TaskState.COMPLETED, snapshot.state());
                assertNull(snapshot.callTree());
                assertEquals(1, sendCalls.get());
                assertEquals(1, getCalls.get());
                assertEquals(List.of("false"), returnImmediately);
            }
        }
    }

    @Test
    void blockingContinueInputKeepsInitialMode() throws Exception {
        AtomicInteger sendCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        List<String> returnImmediately = new CopyOnWriteArrayList<>();
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) -> {
            String method = request.path("method").asText();
            if ("SendMessage".equals(method)) {
                sendCalls.incrementAndGet();
                returnImmediately.add(Boolean.toString(request.path("params").path("configuration")
                        .path("returnImmediately").asBoolean(false)));
                String input = request.path("params").path("message").path("parts").path(0).path("text").asText("");
                String state = "need user input".equals(input) ? "TASK_STATE_INPUT_REQUIRED" : "TASK_STATE_COMPLETED";
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking-input", state));
                return;
            } else if ("GetTask".equals(method)) {
                getCalls.incrementAndGet();
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking-input", "TASK_STATE_COMPLETED"));
                return;
            } else {
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "strict-blocking-input", "TASK_STATE_COMPLETED"));
            }
        })) {
            try (AgentClient client = AgentClients.builder()
                    .transport(new A2aHttpTransportProvider(server.baseUrl(), A2aHttpTestSupport.MAPPER,
                            Duration.ofSeconds(5)))
                    .build()) {
                InvocationCall initial = client.invoke(InvocationRequest.builder()
                        .conversationId("strict-blocking-input")
                        .mode(InvocationMode.BLOCKING)
                        .input("need user input")
                        .build());
                InvocationSnapshot waiting = initial.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(TaskState.INPUT_REQUIRED, waiting.state());
                assertFalse(waiting.terminal());

                InvocationSnapshot completed = client.continueInput(ContinueInputRequest.builder()
                                .conversationId("strict-blocking-input")
                                .relatedInvocationRef(initial.invocationRef())
                                .mode(InvocationMode.BLOCKING)
                                .input("user answer")
                                .build())
                        .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(TaskState.COMPLETED, completed.state());
                assertEquals(2, sendCalls.get());
                assertEquals(0, getCalls.get());
                assertEquals(List.of("false", "false"), returnImmediately);
            }
        }
    }

    @Test
    void runtimeAsyncCompletesOnExplicitQuery() throws Exception {
        AtomicInteger sendCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        List<String> returnImmediately = new CopyOnWriteArrayList<>();
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) -> {
            String method = request.path("method").asText();
            if ("SendMessage".equals(method)) {
                sendCalls.incrementAndGet();
                returnImmediately.add(Boolean.toString(request.path("params").path("configuration")
                        .path("returnImmediately").asBoolean(false)));
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "async-create", "TASK_STATE_WORKING"));
                return;
            } else if ("GetTask".equals(method)) {
                getCalls.incrementAndGet();
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "async-create", "TASK_STATE_COMPLETED"));
                return;
            } else {
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.taskBody("task-working",
                        "async-create", "TASK_STATE_WORKING"));
            }
        })) {
            try (AgentClient client = AgentClients.builder()
                    .transport(new A2aHttpTransportProvider(server.baseUrl(), A2aHttpTestSupport.MAPPER,
                            Duration.ofSeconds(5), RuntimeEndpointPolicy.INSTANCE,
                            Duration.ofSeconds(5), Duration.ofMillis(20)))
                    .build()) {
                InvocationCall call = client.invoke(InvocationRequest.builder()
                        .conversationId("async-create")
                        .mode(InvocationMode.ASYNC)
                        .input("return a working task")
                        .build());
                call.accepted().toCompletableFuture().get(3, TimeUnit.SECONDS);
                TimeUnit.MILLISECONDS.sleep(100);

                assertEquals(1, sendCalls.get());
                assertEquals(List.of("true"), returnImmediately);
                assertEquals(0, getCalls.get());
                assertFalse(call.completion().toCompletableFuture().isDone());

                InvocationSnapshot queried = client.getInvocation(call.invocationRef())
                        .toCompletableFuture().get(3, TimeUnit.SECONDS);
                InvocationSnapshot completed = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(TaskState.COMPLETED, queried.state());
                assertEquals(TaskState.COMPLETED, completed.state());
                assertEquals(1, getCalls.get());
                assertNull(completed.callTree());
            }
        }
    }

    @Test
    void runtimeBlockingTimeoutCanStillBeQueried() throws Exception {
        AtomicInteger sendCalls = new AtomicInteger();
        AtomicInteger getCalls = new AtomicInteger();
        try (A2aHttpTestSupport.TestServer server = A2aHttpTestSupport.start((request, exchange) -> {
            String method = request.path("method").asText();
            if ("SendMessage".equals(method)) {
                sendCalls.incrementAndGet();
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.runtimeTimeoutBody("TASK_STATE_WORKING"));
                return;
            } else if ("GetTask".equals(method)) {
                getCalls.incrementAndGet();
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.runtimeTimeoutBody("TASK_STATE_WORKING"));
                return;
            } else {
                A2aHttpTestSupport.json(exchange, A2aHttpTestSupport.runtimeTimeoutBody("TASK_STATE_WORKING"));
            }
        })) {
            try (AgentClient client = AgentClients.builder()
                    .transport(new A2aHttpTransportProvider(server.baseUrl(), A2aHttpTestSupport.MAPPER,
                            Duration.ofSeconds(5), RuntimeEndpointPolicy.INSTANCE,
                            Duration.ofMillis(120), Duration.ofMillis(20)))
                    .build()) {
                InvocationCall call = client.invoke(InvocationRequest.builder()
                        .conversationId("runtime-timeout")
                        .mode(InvocationMode.BLOCKING)
                        .input("keep working")
                        .build());
                call.accepted().toCompletableFuture().get(1, TimeUnit.SECONDS);
                ExecutionException thrown = assertThrows(ExecutionException.class,
                        () -> call.completion().toCompletableFuture().get(2, TimeUnit.SECONDS));
                assertTrue(thrown.getCause() instanceof ObservationTimeoutException);
                assertEquals(1, sendCalls.get());
                assertTrue(getCalls.get() > 0);
            }
        }
    }
}
