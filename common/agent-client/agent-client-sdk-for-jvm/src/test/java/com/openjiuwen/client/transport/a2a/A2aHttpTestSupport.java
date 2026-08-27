/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

final class A2aHttpTestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private A2aHttpTestSupport() {
    }

    @FunctionalInterface
    interface JsonRpcHandler {
        void handle(JsonNode request, HttpExchange exchange) throws IOException;
    }

    static TestServer start(JsonRpcHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange -> handler.handle(read(exchange), exchange));
        server.start();
        return new TestServer(server);
    }

    static JsonNode read(HttpExchange exchange) throws IOException {
        return MAPPER.readTree(exchange.getRequestBody());
    }

    static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
        exchange.close();
    }

    static void sse(HttpExchange exchange, String frame) throws IOException {
        byte[] payload = ("event: jsonrpc\ndata: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().flush();
        exchange.getResponseBody().close();
        exchange.close();
    }

    static String baseUrl(TestServer server) {
        return "http://127.0.0.1:" + server.port();
    }

    static String taskBody(String taskId, String contextId, String state) {
        return taskBody(taskId, contextId, state, "");
    }

    static String taskBody(String taskId, String contextId, String state, String extraJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"result\",\"result\":{\"task\":{"
                + "\"id\":\"" + taskId + "\",\"contextId\":\"" + contextId + "\","
                + "\"status\":{\"state\":\"" + state + "\"}" + extraJson + "}}}";
    }

    static String taskFrame(String taskId, String contextId, String state, String extraJson) {
        return "{\"jsonrpc\":\"2.0\",\"result\":{\"task\":{"
                + "\"id\":\"" + taskId + "\",\"contextId\":\"" + contextId + "\","
                + "\"status\":{\"state\":\"" + state + "\"}" + extraJson + "}}}";
    }

    static String runtimeTimeoutBody(String state) {
        return taskBody("task-working", "runtime-timeout", state);
    }

    static String resumeTimeoutBody(String state) {
        return taskBody("task-resume-timeout", "resume-timeout", state);
    }

    static String streamingBody(String contextId, String state) {
        return taskBody("task-streaming", contextId, state);
    }

    static String inputRequiredWithClientTool() {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"tool\",\"result\":{\"task\":{"
                + "\"id\":\"task-tool\",\"contextId\":\"incomplete-tool\","
                + "\"status\":{\"state\":\"TASK_STATE_INPUT_REQUIRED\",\"message\":{"
                + "\"metadata\":{\"_interrupt\":{\"context\":{\"_interrupt_kind\":\"client_tool\"}}}}}}}}}";
    }

    static String completedArtifacts() {
        return ",\"artifacts\":[{\"artifactId\":\"a-1\",\"parts\":["
                + "{\"text\":\"target runtime received: hello\"},"
                + "{\"data\":{\"content\":\"[trace=trace-11][agent=demo-a2a-agent-a]\"}},"
                + "{\"data\":{\"text\":\"not-output\",\"message\":\"not-output\"}}]}]";
    }

    static final class TestServer implements AutoCloseable {
        private final HttpServer server;

        private TestServer(HttpServer server) {
            this.server = server;
        }

        int port() {
            return server.getAddress().getPort();
        }

        String baseUrl() {
            return A2aHttpTestSupport.baseUrl(this);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
