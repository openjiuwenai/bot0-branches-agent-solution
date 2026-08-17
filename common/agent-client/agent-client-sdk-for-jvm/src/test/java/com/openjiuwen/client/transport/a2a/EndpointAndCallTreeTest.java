/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.client.api.AgentClient;
import com.openjiuwen.client.api.AgentClients;
import com.openjiuwen.client.api.EndpointType;
import com.openjiuwen.client.api.InvocationCall;
import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.InvocationRequest;
import com.openjiuwen.client.api.InvocationSnapshot;
import com.openjiuwen.client.api.calltree.Completeness;
import com.openjiuwen.client.api.calltree.SpeakingPhase;
import com.openjiuwen.client.transport.spi.CredentialProvider;
import com.openjiuwen.client.transport.spi.TransportProvider;
import com.openjiuwen.client.transport.spi.ToolWireSpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test for EndpointAndCallTreeTest.
 *
 * @since 2026-07-27
 */
class EndpointAndCallTreeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void runtimePolicyDropsAllAttributesButKeepsClientTools() {
        ToolWireSpec tool = new ToolWireSpec("lookup", "lookup data", "{\"type\":\"object\"}");
        TransportProvider.CreateCommand source = new TransportProvider.CreateCommand(
                "inv", "inv", "key", "ctx", "agent-a", InvocationMode.ASYNC, "hello",
                List.of(tool), "secret", null,
                Map.of("traceId", "trace-1", "tenant_id", "tenant-a", "routingAgent", "agent-b"));

        TransportProvider.CreateCommand projected = RuntimeEndpointPolicy.INSTANCE.createCommand(source);

        assertTrue(projected.attributes().isEmpty());
        assertEquals(List.of(tool), projected.clientTools());
        assertNull(projected.agentId());
        assertNull(projected.credentialToken());
    }

    @Test
    void runtimeBuilderOmitsGatewayIdentityAndDoesNotBuildBlockingTree() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            request.set(MAPPER.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            json(exchange, taskResponse("task-runtime", "TASK_STATE_COMPLETED", "root answer", null));
        });
        try (AgentClient client = AgentClients.builder()
                .endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server))
                .credentialProvider(CredentialProvider.staticToken("secret"))
                .build()) {
            InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder()
                    .agentId("must-not-leak")
                    .conversationId("runtime-policy")
                    .mode(InvocationMode.BLOCKING)
                    .credentialToken("request-secret")
                    .attribute("traceId", "trace-1")
                    .attribute("tenantId", "must-not-leak")
                    .attribute("Authorization", "must-not-leak")
                    .input("hello")
                    .build()).completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertNull(authorization.get());
            assertFalse(request.get().path("params").path("metadata").has("agentId"));
            assertFalse(request.get().path("params").path("metadata").has("attributes"),
                    "Runtime direct mode must not forward arbitrary request attributes");
            assertEquals("root answer", snapshot.outputText());
            assertNull(snapshot.callTree(), "BLOCKING must not construct a call tree");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void gatewayBuilderPreservesBearerAndOptionalAgentId() throws Exception {
        AtomicReference<JsonNode> request = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            request.set(MAPPER.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            json(exchange, taskResponse("task-gateway", "TASK_STATE_COMPLETED", "done", null));
        });
        try (AgentClient client = AgentClients.builder()
                .endpointUrl(url(server))
                .credentialProvider(CredentialProvider.staticToken("secret"))
                .build()) {
            client.invoke(InvocationRequest.builder().agentId("agent-a").conversationId("gateway-policy")
                    .mode(InvocationMode.BLOCKING).input("hello").build())
                    .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals("Bearer secret", authorization.get());
            assertEquals("agent-a", request.get().path("params").path("metadata").path("agentId").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamingBuildsInterleavedTreeAndFiltersRootOutput() throws Exception {
        HttpServer server = server(exchange -> sse(exchange,
                frame("1", delegation("root-task", "agent-a", "root-task", "agent-b1", "task-b1")),
                frame("2", delegation("root-task", "agent-a", "root-task", "agent-b2", "task-b2")),
                frame("3", output("root-task", "agent-b1", "task-b1", "b1 says")),
                frame("4", output("root-task", "agent-b2", "task-b2", "b2 says")),
                frame("5", controller("root-task")),
                frame("6", completed("root-task"))));
        try (AgentClient client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server)).build()) {
            InvocationCall call = client.invoke(InvocationRequest.builder().conversationId("tree")
                    .mode(InvocationMode.STREAMING).input("hello").build());
            InvocationSnapshot snapshot = call.completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertNull(snapshot.outputText(), "descendant and controller output must not pollute root output");
            assertEquals(2, snapshot.callTree().root().children().size());
            var part0 = snapshot.callTree().root().children().get(0).artifacts().get(0).parts().get(0);
            if (part0 instanceof com.openjiuwen.client.api.calltree.TextPartSnapshot textPart) {
                assertEquals("b1 says", textPart.text());
            }
            assertEquals(SpeakingPhase.ROOT_SPEAKING, snapshot.callTree().speakingPhase());
            assertEquals("root-task", snapshot.callTree().currentSpeaker().taskId());
            assertEquals("completed", snapshot.callTree().root().state());
            assertEquals(Completeness.LIVE, snapshot.callTree().completeness());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void streamingRootOutputHonorsArtifactReplaceAndAppend() throws Exception {
        HttpServer server = server(exchange -> sse(exchange,
                frame("1", rootArtifact("root-output", "a", "old", false, false)),
                frame("2", rootArtifact("root-output", "b", "tail", false, true)),
                frame("3", rootArtifact("root-output", "a", "new", false, false)),
                frame("4", rootArtifact("root-output", "a", "!", true, true)),
                frame("5", completed("root-output"))));
        try (AgentClient client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server)).build()) {
            InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder()
                    .conversationId("root-replace").mode(InvocationMode.STREAMING).input("hello").build())
                    .completion().toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals("new!tail", snapshot.outputText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void builderRejectsAmbiguousTransportConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> AgentClients.builder()
                .endpointUrl("http://localhost:1")
                .transport(new RuntimeTransportProvider("http://localhost:2"))
                .build());
    }

    @Test
    void codecMapsAllInvocationModesToExpectedWireContract() {
        A2aJsonCodec codec = new A2aJsonCodec(MAPPER);
        JsonNode streaming = codec.buildCreate(command(InvocationMode.STREAMING));
        JsonNode blocking = codec.buildCreate(command(InvocationMode.BLOCKING));
        JsonNode async = codec.buildCreate(command(InvocationMode.ASYNC));

        assertEquals("SendStreamingMessage", streaming.path("method").asText());
        assertFalse(streaming.path("params").has("configuration"));
        assertEquals("SendMessage", blocking.path("method").asText());
        assertFalse(blocking.path("params").path("configuration").path("returnImmediately").asBoolean());
        assertEquals("SendMessage", async.path("method").asText());
        assertEquals(true, async.path("params").path("configuration").path("returnImmediately").asBoolean());
    }

    @Test
    void codecMarksClientToolInterruptWithoutResumeTargetInvalid() {
        A2aJsonCodec codec = new A2aJsonCodec(MAPPER);
        JsonNode result = MAPPER.valueToTree(Map.of("task", Map.of(
                "id", "task-incomplete", "contextId", "ctx",
                "status", Map.of("state", "TASK_STATE_INPUT_REQUIRED",
                        "message", Map.of("metadata", Map.of("_interrupt", Map.of(
                                "context", Map.of("_interrupt_kind", "client_tool"))))))));

        A2aJsonCodec.Frame frame = codec.parseFrame(result).orElseThrow();

        assertNotNull(frame.interrupt());
        assertFalse(frame.interrupt().validResumeTarget());
    }

    @Test
    void runtimeReconnectsWithTaskSubscriptionWithoutCursor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> methods = new CopyOnWriteArrayList<>();
        AtomicReference<String> lastEventId = new AtomicReference<>();
        CountDownLatch subscribed = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            methods.add(request.path("method").asText());
            if (calls.getAndIncrement() == 0) {
                sse(exchange,
                        frame("1", delegation("root-replay", "agent-a", "root-replay", "agent-b", "task-b")),
                        frame("2", output("root-replay", "agent-b", "task-b", "before disconnect")));
            } else {
                lastEventId.set(exchange.getRequestHeaders().getFirst("Last-Event-ID"));
                subscribed.countDown();
                sse(exchange, frame("3", completed("root-replay")));
            }
        });
        try (AgentClient client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server)).build()) {
            InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder().conversationId("replay")
                    .mode(InvocationMode.STREAMING).input("hello").build())
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertNull(snapshot.recovery(), String.valueOf(snapshot.recovery()));
            subscribed.await(3, TimeUnit.SECONDS);
            assertEquals(java.util.List.of("SendStreamingMessage", "SubscribeToTask"), methods);
            assertNull(lastEventId.get(), "Runtime subscription must not send Last-Event-ID");
            assertEquals(Completeness.PARTIAL, snapshot.callTree().completeness());
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void terminalSubscriptionFallsBackToDirectCurrentTaskSnapshot() throws Exception {
        CopyOnWriteArrayList<String> methods = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            methods.add(request.path("method").asText());
            switch (calls.getAndIncrement()) {
                case 0 -> sse(exchange,
                        frame("1", delegation("root-expired", "agent-a", "root-expired",
                                "agent-b", "task-b")));
                case 1 -> json(exchange, "{\"jsonrpc\":\"2.0\",\"id\":\"rpc\",\"error\":{"
                        + "\"code\":-32004,\"message\":\"task is already terminal\"}}");
                default -> json(exchange,
                        directTaskResponse("root-expired", "TASK_STATE_COMPLETED", "root done"));
            }
        });
        try (AgentClient client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server)).build()) {
            InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder().conversationId("expired")
                    .mode(InvocationMode.STREAMING).input("hello").build())
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(java.util.List.of("SendStreamingMessage", "SubscribeToTask", "GetTask"), methods);
            assertEquals("root done", snapshot.outputText());
            assertEquals(Completeness.PARTIAL, snapshot.callTree().completeness());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void directGetTaskParsesArtifactsAndBuildsPartialRecoveredTree() throws Exception {
        HttpServer server = server(exchange -> json(exchange,
                directTaskResponse("root-direct", "TASK_STATE_COMPLETED", "root result")));
        try (RuntimeTransportProvider transport = new RuntimeTransportProvider(url(server))) {
            InvocationSnapshot snapshot = transport.getTask("root-direct", null)
                    .toCompletableFuture().get(3, TimeUnit.SECONDS);

            assertEquals("root result", snapshot.outputText());
            assertEquals(com.openjiuwen.client.api.TaskState.COMPLETED, snapshot.state());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void malformedSubscriptionFrameFallsBackToGetTaskAndSettles() throws Exception {
        CopyOnWriteArrayList<String> methods = new CopyOnWriteArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            methods.add(request.path("method").asText());
            switch (calls.getAndIncrement()) {
                case 0 -> sse(exchange, frame("ignored", delegation("root-malformed", "agent-a",
                        "root-malformed", "agent-b", "task-b")));
                case 1 -> sse(exchange, "event:jsonrpc\ndata:{not-json}\n\n");
                default -> json(exchange,
                        directTaskResponse("root-malformed", "TASK_STATE_COMPLETED", "reconciled"));
            }
        });
        try (AgentClient client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server)).build()) {
            InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder().conversationId("malformed")
                    .mode(InvocationMode.STREAMING).input("hello").build())
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(java.util.List.of("SendStreamingMessage", "SubscribeToTask", "GetTask"), methods);
            assertEquals("reconciled", snapshot.outputText());
            assertEquals(Completeness.PARTIAL, snapshot.callTree().completeness());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void runtimeFailsCreateWithoutTaskIdAndDoesNotRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });
        try (AgentClient client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server)).build()) {
            InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder().conversationId("unknown")
                    .mode(InvocationMode.STREAMING).input("hello").build())
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(1, calls.get());
            assertEquals(com.openjiuwen.client.api.TaskState.FAILED, snapshot.state());
            assertEquals(com.openjiuwen.client.api.ErrorCodes.CREATE_FAILED_NO_TASK_ID, snapshot.errorCode());
            assertNull(snapshot.recovery());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void threeConsecutiveSubscribeFailuresOpenRecoveryCircuit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = server(exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (calls.getAndIncrement() == 0) {
                sse(exchange, frame("1", delegation("root-circuit", "agent-a", "root-circuit",
                        "agent-b", "task-b")));
            } else {
                byte[] body = "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"retry\"}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(503, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            }
        });
        try (AgentClient client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server)).build()) {
            InvocationCall call = client.invoke(InvocationRequest.builder().conversationId("circuit")
                    .mode(InvocationMode.STREAMING).input("hello").build());
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> call.completion().toCompletableFuture().get(5, TimeUnit.SECONDS));

            assertEquals(4, calls.get());
            assertTrue(failure.getCause().getMessage().contains("recovery failed 3 times"));
            assertEquals("RECOVERY_RETRY_EXHAUSTED",
                    failure.getCause() instanceof com.openjiuwen.client.api.ClassifiedError classified
                            ? classified.code() : null);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void successfulGetTaskReconciliationResetsSubscribeFailureCounter() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CopyOnWriteArrayList<String> methods = new CopyOnWriteArrayList<>();
        HttpServer server = server(exchange -> {
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText();
            methods.add(method);
            int call = calls.getAndIncrement();
            if (call == 0) {
                sse(exchange, frame("1", delegation("root-reset", "agent-a", "root-reset",
                        "agent-b", "task-b")));
            } else if ("SubscribeToTask".equals(method)) {
                serviceUnavailable(exchange);
            } else if (call < 6) {
                json(exchange, directTaskResponse("root-reset", "TASK_STATE_WORKING", "working"));
            } else {
                json(exchange, directTaskResponse("root-reset", "TASK_STATE_COMPLETED", "done"));
            }
        });
        try (AgentClient client = AgentClients.builder().endpointType(EndpointType.RUNTIME)
                .endpointUrl(url(server)).build()) {
            InvocationSnapshot snapshot = client.invoke(InvocationRequest.builder().conversationId("reset")
                    .mode(InvocationMode.STREAMING).input("hello").build())
                    .completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals("done", snapshot.outputText());
            assertEquals(java.util.List.of("SendStreamingMessage", "SubscribeToTask", "GetTask",
                    "SubscribeToTask", "GetTask", "SubscribeToTask", "GetTask"), methods);
        } finally {
            server.stop(0);
        }
    }

    private static TransportProvider.CreateCommand command(InvocationMode mode) {
        return new TransportProvider.CreateCommand("inv", "message", "message", "conversation",
                "agent", mode, "hello", java.util.List.of(), null, null, java.util.Map.of());
    }

    private static HttpServer server(Handler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static String url(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void serviceUnavailable(HttpExchange exchange) throws IOException {
        byte[] bytes = "{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"retry\"}"
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(503, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sse(HttpExchange exchange, String... events) throws IOException {
        exchange.getRequestBody().readAllBytes();
        String body = String.join("", events);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String frame(String id, String result) {
        return "event: jsonrpc\ndata: {\"jsonrpc\":\"2.0\",\"id\":\"rpc\",\"result\":"
                + result + "}\n\n";
    }

    private static String directTaskResponse(String taskId, String state, String rootText) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"rpc\",\"result\":{\"id\":\""
                + taskId + "\",\"contextId\":\"ctx\",\"status\":{\"state\":\"" + state
                + "\"},\"artifacts\":[{\"artifactId\":\"root\",\"parts\":[{\"text\":\""
                + rootText + "\"}]}]}}";
    }

    private static String delegation(String outerTask, String sourceAgent, String sourceTask,
            String targetAgent, String targetTask) {
        return artifact(outerTask, "delegation-" + targetTask, "{\"text\":\"delegate\"}",
                "{\"type\":\"delegation\",\"source\":{\"agentId\":\"" + sourceAgent
                        + "\",\"taskId\":\"" + sourceTask + "\"},\"target\":{\"agentId\":\""
                        + targetAgent + "\",\"taskId\":\"" + targetTask + "\"}}");
    }

    private static String output(String outerTask, String agent, String task, String text) {
        return artifact(outerTask, "out-" + task, "{\"text\":\"" + text + "\"}",
                "{\"type\":\"output\",\"source\":{\"agentId\":\"" + agent
                        + "\",\"taskId\":\"" + task + "\"}}");
    }

    private static String artifact(String outerTask, String artifactId, String part, String agentEvent) {
        return "{\"artifactUpdate\":{\"taskId\":\"" + outerTask
                + "\",\"contextId\":\"tree\",\"artifact\":{\"artifactId\":\"" + artifactId
                + "\",\"parts\":[" + part + "],\"metadata\":{\"agentEvent\":" + agentEvent
                + "}},\"append\":false,\"lastChunk\":true}}";
    }

    private static String rootArtifact(String task, String artifactId, String text,
            boolean append, boolean lastChunk) {
        return "{\"artifactUpdate\":{\"taskId\":\"" + task
                + "\",\"contextId\":\"tree\",\"artifact\":{\"artifactId\":\"" + artifactId
                + "\",\"parts\":[{\"text\":\"" + text + "\"}]},\"append\":" + append
                + ",\"lastChunk\":" + lastChunk + "}}";
    }

    private static String controller(String task) {
        return "{\"artifactUpdate\":{\"taskId\":\"" + task
                + "\",\"contextId\":\"tree\",\"artifact\":{\"artifactId\":\"controller\","
                + "\"parts\":[{\"data\":{\"type\":\"controller_output\",\"payload\":{"
                + "\"type\":\"all_tasks_processed\"}}}]}}}";
    }

    private static String completed(String task) {
        return "{\"statusUpdate\":{\"taskId\":\"" + task
                + "\",\"contextId\":\"tree\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                + "\"final\":true}}";
    }

    private static String taskResponse(String taskId, String state, String rootText, String extraArtifacts) {
        String artifacts = "[{\"artifactId\":\"root\",\"parts\":[{\"text\":\"" + rootText + "\"}]}]";
        if (extraArtifacts != null) {
            artifacts = extraArtifacts;
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":\"rpc\",\"result\":{\"task\":{\"id\":\""
                + taskId + "\",\"contextId\":\"ctx\",\"status\":{\"state\":\"" + state
                + "\"},\"artifacts\":" + artifacts + "}}}";
    }

    @FunctionalInterface
    private interface Handler {
        /**
         * 处理 HTTP 请求。
         *
         * @param exchange HTTP 交换
         * @throws IOException IO 异常
         */
        void handle(HttpExchange exchange) throws IOException;
    }
}
