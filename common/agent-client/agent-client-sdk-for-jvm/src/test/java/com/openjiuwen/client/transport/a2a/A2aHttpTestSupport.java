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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shared HTTP test scaffolding for A2A transport tests.
 *
 * @since 2026-08-27
 */
final class A2aHttpTestSupport {
    static final ObjectMapper MAPPER = new ObjectMapper();

    private A2aHttpTestSupport() {
    }

    @FunctionalInterface
    interface JsonRpcHandler {
        /**
         * Handles one JSON-RPC request.
         *
         * @param request parsed request
         * @param exchange HTTP exchange
         * @throws IOException if response writing fails
         */
        void handle(JsonNode request, HttpExchange exchange) throws IOException;
    }

    /**
     * Starts a local mock A2A server.
     *
     * @param handler request handler
     * @return started test server
     * @throws IOException if the server cannot be created
     */
    static TestServer start(JsonRpcHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange -> handler.handle(read(exchange), exchange));
        server.start();
        return new TestServer(server);
    }

    /**
     * Reads the request body as JSON.
     *
     * @param exchange HTTP exchange
     * @return parsed JSON
     * @throws IOException if the body cannot be parsed
     */
    static JsonNode read(HttpExchange exchange) throws IOException {
        return MAPPER.readTree(exchange.getRequestBody());
    }

    /**
     * Writes a JSON response.
     *
     * @param exchange HTTP exchange
     * @param body response body
     * @throws IOException if writing fails
     */
    static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
        exchange.close();
    }

    /**
     * Writes one SSE frame and closes the stream.
     *
     * @param exchange HTTP exchange
     * @param frame SSE frame payload
     * @throws IOException if writing fails
     */
    static void sse(HttpExchange exchange, String frame) throws IOException {
        byte[] payload = ("event: jsonrpc\ndata: " + frame + "\n\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().flush();
        exchange.getResponseBody().close();
        exchange.close();
    }

    /**
     * Returns the server base URL.
     *
     * @param server test server
     * @return base URL
     */
    static String baseUrl(TestServer server) {
        return "http://127.0.0.1:" + server.port();
    }

    /**
     * Builds a task response body.
     *
     * @param taskId task id
     * @param contextId context id
     * @param state task state
     * @return response body
     */
    static String taskBody(String taskId, String contextId, String state) {
        return taskBody(taskId, contextId, state, "");
    }

    /**
     * Builds a task response body with extra JSON fields.
     *
     * @param taskId task id
     * @param contextId context id
     * @param state task state
     * @param extraJson trailing JSON fragment
     * @return response body
     */
    static String taskBody(String taskId, String contextId, String state, String extraJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":\"result\",\"result\":{\"task\":{"
                + "\"id\":\"" + taskId + "\",\"contextId\":\"" + contextId + "\","
                + "\"status\":{\"state\":\"" + state + "\"}" + extraJson + "}}}";
    }

    /**
     * Builds a task frame for streaming tests.
     *
     * @param taskId task id
     * @param contextId context id
     * @param state task state
     * @param extraJson trailing JSON fragment
     * @return frame body
     */
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

    /**
     * Creates a bounded single-thread executor for observer tests.
     *
     * @param threadName worker thread name
     * @return executor service
     */
    static ExecutorService observerExecutor(String threadName) {
        ThreadFactory factory = r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName(threadName);
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), factory);
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
