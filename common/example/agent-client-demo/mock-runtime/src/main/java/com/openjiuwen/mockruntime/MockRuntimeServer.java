/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.mockruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Scriptable A2A Runtime used only for local agent-client verification. */
public final class MockRuntimeServer {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> SCENARIOS = List.of(
            "single", "nested-5", "parallel-interleave", "multi-artifact", "output-before-edge",
            "input-linear", "input-status-incomplete", "client-tool", "disconnect-replay",
            "replay-duplicate", "cursor-expired", "mode-unavailable", "recovery-circuit",
            "runtime-create-unknown", "controller-return", "speaking-handoff", "root-output-filter",
            "malformed-graph");

    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    private final List<RequestRecord> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger requestSequence = new AtomicInteger();
    private final int port;

    private MockRuntimeServer(int port) {
        this.port = port;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            redirectOutput(args[1]);
        }
        String configured = args.length > 0 ? args[0]
                : System.getenv().getOrDefault("MOCK_RUNTIME_PORT", "19090");
        int port = Integer.parseInt(configured);
        new MockRuntimeServer(port).start();
    }

    private static void redirectOutput(String path) throws IOException {
        PrintStream log = new PrintStream(path, StandardCharsets.UTF_8);
        System.out.close();
        System.err.close();
        System.setOut(log);
        System.setErr(log);
    }

    private void start() throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread thread = new Thread(r, "mock-runtime-http");
            thread.setDaemon(true);
            return thread;
        }));
        server.createContext("/a2a", this::handleA2a);
        server.createContext("/admin/health", exchange -> json(exchange, 200, Map.of(
                "status", "UP", "service", "mock-runtime", "port", port,
                "tasks", tasks.size(), "requests", requests.size())));
        server.createContext("/admin/scenarios", exchange -> json(exchange, 200, SCENARIOS));
        server.createContext("/admin/requests", this::handleRequests);
        server.createContext("/admin/tasks", exchange -> json(exchange, 200,
                tasks.values().stream().sorted(Comparator.comparing(TaskRecord::createdAt).reversed())
                        .map(TaskRecord::summary).toList()));
        server.createContext("/admin/reset", exchange -> {
            tasks.clear();
            requests.clear();
            json(exchange, 200, Map.of("reset", true));
        });
        server.start();
        System.out.println("Mock Runtime listening on http://127.0.0.1:" + port);
        System.out.println("A2A endpoint: http://127.0.0.1:" + port + "/a2a");
        Thread.currentThread().join();
    }

    private void handleA2a(HttpExchange exchange) throws IOException {
        addCors(exchange);
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return;
        }
        byte[] raw = exchange.getRequestBody().readAllBytes();
        JsonNode request;
        try {
            request = JSON.readTree(raw);
        } catch (Exception error) {
            jsonRpcError(exchange, null, -32700, "PARSE_ERROR", error.getMessage());
            return;
        }
        String method = request.path("method").asText();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String cursor = exchange.getRequestHeaders().getFirst("Last-Event-ID");
        requests.add(new RequestRecord(requestSequence.incrementAndGet(), Instant.now().toString(), method,
                authorization, cursor, JSON.convertValue(request, Object.class)));
        switch (method) {
            case "SendStreamingMessage" -> sendStreaming(exchange, request);
            case "SendMessage" -> sendMessage(exchange, request);
            case "GetTask" -> getTask(exchange, request);
            case "SubscribeToTask" -> subscribe(exchange, request, cursor);
            default -> jsonRpcError(exchange, request.path("id").asText(), -32601,
                    "METHOD_NOT_FOUND", "unsupported method " + method);
        }
    }

    private void sendStreaming(HttpExchange exchange, JsonNode request) throws IOException {
        JsonNode message = request.path("params").path("message");
        String existingTask = message.path("taskId").asText(null);
        if (existingTask != null) {
            TaskRecord task = tasks.get(existingTask);
            if (task == null) {
                jsonRpcError(exchange, request.path("id").asText(), -32001, "TASK_NOT_FOUND", existingTask);
                return;
            }
            task.completeAfterResume(text(message));
            sse(exchange, task, task.resumeStartIndex, false, false);
            return;
        }
        String scenario = scenario(request);
        if ("runtime-create-unknown".equals(scenario)) {
            exchange.close();
            return;
        }
        TaskRecord task = TaskRecord.create(message.path("contextId").asText("context"), scenario);
        tasks.put(task.id, task);
        if ("disconnect-replay".equals(scenario) || "replay-duplicate".equals(scenario)
                || "cursor-expired".equals(scenario) || "recovery-circuit".equals(scenario)) {
            sse(exchange, task, 0, false, true);
        } else {
            sse(exchange, task, 0, false, false);
        }
    }

    private void sendMessage(HttpExchange exchange, JsonNode request) throws IOException {
        JsonNode message = request.path("params").path("message");
        String existingTask = message.path("taskId").asText(null);
        TaskRecord task;
        if (existingTask != null) {
            task = tasks.get(existingTask);
            if (task == null) {
                jsonRpcError(exchange, request.path("id").asText(), -32001, "TASK_NOT_FOUND", existingTask);
                return;
            }
            task.completeAfterResume(text(message));
        } else {
            task = TaskRecord.create(message.path("contextId").asText("context"), scenario(request));
            tasks.put(task.id, task);
        }
        boolean immediately = request.path("params").path("configuration")
                .path("returnImmediately").asBoolean(false);
        if (immediately) {
            task.asyncQueriesRemaining.set(1);
            task.state = "TASK_STATE_WORKING";
        } else if (!"input-linear".equals(task.scenario)) {
            task.state = "TASK_STATE_COMPLETED";
        }
        jsonRpcResult(exchange, request.path("id").asText(), task.taskNode());
    }

    private void getTask(HttpExchange exchange, JsonNode request) throws IOException {
        TaskRecord task = tasks.get(request.path("params").path("id").asText());
        if (task == null) {
            jsonRpcError(exchange, request.path("id").asText(), -32001, "TASK_NOT_FOUND", "task not found");
            return;
        }
        if (task.asyncQueriesRemaining.getAndUpdate(value -> Math.max(0, value - 1)) == 0
                && "TASK_STATE_WORKING".equals(task.state)) {
            task.state = "TASK_STATE_COMPLETED";
        }
        jsonRpcResult(exchange, request.path("id").asText(), task.taskNode());
    }

    private void subscribe(HttpExchange exchange, JsonNode request, String cursor) throws IOException {
        TaskRecord task = tasks.get(request.path("params").path("id").asText());
        if (task == null) {
            jsonRpcError(exchange, request.path("id").asText(), -32001, "TASK_NOT_FOUND", "task not found");
            return;
        }
        int attempt = task.subscribeAttempts.incrementAndGet();
        if ("recovery-circuit".equals(task.scenario)) {
            json(exchange, 503, Map.of("code", "SERVICE_UNAVAILABLE", "message", "injected failure " + attempt));
            return;
        }
        if ("cursor-expired".equals(task.scenario) && attempt == 1) {
            ObjectNode root = JSON.createObjectNode();
            root.put("jsonrpc", "2.0").put("id", request.path("id").asText());
            ObjectNode error = root.putObject("error");
            error.put("code", -32010).put("message", "cursor expired");
            error.putObject("data").put("code", "CURSOR_EXPIRED");
            json(exchange, 200, root);
            return;
        }
        int last = parseCursor(cursor);
        int start = "replay-duplicate".equals(task.scenario) ? Math.max(0, last - 1) : last;
        sse(exchange, task, start, true, false);
    }

    private void sse(HttpExchange exchange, TaskRecord task, int startIndex,
            boolean subscription, boolean disconnect) throws IOException {
        List<Frame> selected = new ArrayList<>();
        int end = disconnect ? Math.min(task.initialFrameCount, task.frames.size()) : task.frames.size();
        for (int index = Math.max(0, startIndex); index < end; index++) {
            selected.add(task.frames.get(index));
        }
        StringBuilder body = new StringBuilder();
        for (Frame frame : selected) {
            body.append("id: ").append(frame.id()).append('\n')
                    .append("data: ").append(frame.payload()).append("\n\n");
        }
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        addCors(exchange);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("X-Mock-Subscription", Boolean.toString(subscription));
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private void handleRequests(HttpExchange exchange) throws IOException {
        addCors(exchange);
        String query = exchange.getRequestURI().getQuery();
        int after = 0;
        if (query != null && query.startsWith("after=")) {
            try {
                after = Integer.parseInt(query.substring("after=".length()));
            } catch (NumberFormatException ignored) {
                after = 0;
            }
        }
        int lower = after;
        json(exchange, 200, requests.stream().filter(record -> record.sequence() > lower).toList());
    }

    private static String scenario(JsonNode request) {
        String value = request.path("params").path("metadata").path("attributes")
                .path("scenario").asText("single");
        return SCENARIOS.contains(value) ? value : "single";
    }

    private static String text(JsonNode message) {
        JsonNode parts = message.path("parts");
        return parts.isArray() && !parts.isEmpty() ? parts.get(0).path("text").asText("") : "";
    }

    private static int parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(cursor);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void jsonRpcResult(HttpExchange exchange, String id, JsonNode task) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0").put("id", id);
        root.putObject("result").set("task", task);
        json(exchange, 200, root);
    }

    private static void jsonRpcError(HttpExchange exchange, String id, int rpcCode,
            String code, String message) throws IOException {
        ObjectNode root = JSON.createObjectNode();
        root.put("jsonrpc", "2.0").put("id", id);
        ObjectNode error = root.putObject("error");
        error.put("code", rpcCode).put("message", message == null ? code : message);
        error.putObject("data").put("code", code);
        json(exchange, 200, root);
    }

    private static void json(HttpExchange exchange, int status, Object value) throws IOException {
        addCors(exchange);
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void addCors(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Last-Event-ID");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
    }

    private record Frame(int id, String payload) {
    }

    private record RequestRecord(int sequence, String receivedAt, String method,
            String authorization, String lastEventId, Object body) {
    }

    private static final class TaskRecord {
        private final String id = "task-" + UUID.randomUUID();
        private final String contextId;
        private final String scenario;
        private final Instant createdAt = Instant.now();
        private final List<Frame> frames = new ArrayList<>();
        private final AtomicInteger subscribeAttempts = new AtomicInteger();
        private final AtomicInteger asyncQueriesRemaining = new AtomicInteger();
        private volatile String state = "TASK_STATE_COMPLETED";
        private volatile String output = "Root agent completed the request.";
        private int initialFrameCount;
        private int resumeStartIndex;

        private TaskRecord(String contextId, String scenario) {
            this.contextId = contextId;
            this.scenario = scenario;
        }

        static TaskRecord create(String contextId, String scenario) {
            TaskRecord task = new TaskRecord(contextId, scenario);
            task.script();
            return task;
        }

        private void script() {
            switch (scenario) {
                case "nested-5" -> nested(5);
                case "parallel-interleave", "speaking-handoff" -> parallel();
                case "multi-artifact" -> multiArtifact();
                case "output-before-edge" -> outputBeforeEdge();
                case "input-linear" -> inputLinear(true);
                case "input-status-incomplete" -> inputLinear(false);
                case "client-tool" -> clientTool();
                case "controller-return" -> controllerReturn();
                case "root-output-filter" -> rootOutputFilter();
                case "mode-unavailable" -> modeUnavailable();
                case "malformed-graph" -> malformed();
                default -> single();
            }
            if (!"input-linear".equals(scenario) && !"input-status-incomplete".equals(scenario)
                    && !"client-tool".equals(scenario)) {
                addStatus("TASK_STATE_COMPLETED", true, null);
            }
            initialFrameCount = Math.min(2, frames.size());
            if (!scenario.contains("disconnect") && !scenario.contains("replay")
                    && !"cursor-expired".equals(scenario) && !"recovery-circuit".equals(scenario)) {
                initialFrameCount = frames.size();
            }
        }

        private void single() {
            addRootText("Root agent is processing the request. ", true, false);
            addRootText("Root agent completed the request.", true, true);
        }

        private void nested(int depth) {
            String sourceTask = id;
            String sourceAgent = "agent-a";
            for (int level = 1; level <= depth; level++) {
                String childTask = id + "-child-" + level;
                String childAgent = "agent-" + (char) ('a' + level);
                addDelegation(sourceAgent, sourceTask, childAgent, childTask,
                        "Delegate level " + level);
                addOutput(childAgent, childTask, "Level " + level + " output", false, true);
                sourceTask = childTask;
                sourceAgent = childAgent;
            }
            addController();
            addRootText("Root resumed after five levels.", false, true);
        }

        private void parallel() {
            addDelegation("agent-a", id, "agent-b1", id + "-b1", "parallel B1");
            addDelegation("agent-a", id, "agent-b2", id + "-b2", "parallel B2");
            addOutput("agent-b1", id + "-b1", "B1 chunk one. ", false, false);
            addOutput("agent-b2", id + "-b2", "B2 answer.", false, true);
            addOutput("agent-b1", id + "-b1", "B1 finished.", true, true);
            addAgentStatus("agent-b1", id + "-b1", "completed");
            addAgentStatus("agent-b2", id + "-b2", "completed");
            addController();
            addRootText("A resumes after both descendants.", false, true);
        }

        private void multiArtifact() {
            addDelegation("agent-a", id, "agent-b", id + "-b", "multi artifact");
            addOutputWithId("child-artifact-1", "agent-b", id + "-b", "first", false, true);
            addOutputWithId("child-artifact-2", "agent-b", id + "-b", "second", false, true);
            addController();
        }

        private void outputBeforeEdge() {
            addOutput("agent-b", id + "-b", "Output arrived before delegation.", false, true);
            addDelegation("agent-a", id, "agent-b", id + "-b", "late edge");
            addController();
        }

        private void inputLinear(boolean completeInterrupt) {
            state = "TASK_STATE_INPUT_REQUIRED";
            addDelegation("agent-a", id, "agent-b", id + "-b", "need account detail");
            addAgentStatus("agent-b", id + "-b", "input_required");
            if (completeInterrupt) {
                addStatus("TASK_STATE_INPUT_REQUIRED", false, interrupt("user_input", "input-" + id,
                        null, "Please provide the account suffix.", null));
            } else {
                addStatus("TASK_STATE_INPUT_REQUIRED", false, null);
            }
            resumeStartIndex = frames.size();
        }

        private void clientTool() {
            state = "TASK_STATE_INPUT_REQUIRED";
            addStatus("TASK_STATE_INPUT_REQUIRED", false, interrupt("client_tool", "tool-" + id,
                    "local.echo", null, Map.of("text", "hello from Runtime")));
            resumeStartIndex = frames.size();
        }

        private void malformed() {
            addDelegation("agent-a", id, "agent-b", id + "-b", "first parent");
            addDelegation("agent-c", id + "-c", "agent-b", id + "-b", "illegal second parent");
            addOutputWithId("shared", "agent-b", id + "-b", "first owner", false, true);
            addOutputWithId("shared", "agent-c", id + "-c", "conflicting owner", false, true);
        }

        private void controllerReturn() {
            addDelegation("agent-a", id, "agent-b", id + "-b", "delegate before control return");
            addOutput("agent-b", id + "-b", "Child speaks before returning control.", false, true);
            addAgentStatus("agent-b", id + "-b", "completed");
            addController();
            addRootText("Root speaks after controller_output.", false, true);
        }

        private void rootOutputFilter() {
            addDelegation("agent-a", id, "agent-b", id + "-b", "child output must stay in tree");
            addOutput("agent-b", id + "-b", "CHILD_ONLY_MARKER", false, true);
            addController();
            addRootText("ROOT_ONLY_MARKER", false, true);
        }

        private void modeUnavailable() {
            addRootText("Unary mode output without process topology.", false, true);
        }

        private void completeAfterResume(String input) {
            state = "TASK_STATE_COMPLETED";
            output = "Root agent resumed with: " + input;
            addController();
            addRootText(output, false, true);
            addStatus("TASK_STATE_COMPLETED", true, null);
        }

        private void addRootText(String text, boolean append, boolean last) {
            addArtifact("root-output", List.of(Map.of("text", text)), null, append, last);
            output = text;
        }

        private void addDelegation(String sourceAgent, String sourceTask, String targetAgent,
                String targetTask, String intent) {
            Map<String, Object> event = Map.of("type", "delegation",
                    "source", Map.of("agentId", sourceAgent, "taskId", sourceTask),
                    "target", Map.of("agentId", targetAgent, "taskId", targetTask));
            addArtifact("delegation-" + targetTask, List.of(Map.of("text", intent)), event, false, true);
        }

        private void addOutput(String agent, String task, String text, boolean append, boolean last) {
            addOutputWithId("output-" + task, agent, task, text, append, last);
        }

        private void addOutputWithId(String artifactId, String agent, String task,
                String text, boolean append, boolean last) {
            Map<String, Object> event = Map.of("type", "output",
                    "source", Map.of("agentId", agent, "taskId", task));
            addArtifact(artifactId, List.of(Map.of("text", text)), event, append, last);
        }

        private void addAgentStatus(String agent, String task, String value) {
            Map<String, Object> event = Map.of("type", "status", "state", value,
                    "source", Map.of("agentId", agent, "taskId", task));
            addArtifact("status-" + task, List.of(Map.of("text", value)), event, false, true);
        }

        private void addController() {
            addArtifact("controller-" + frames.size(), List.of(Map.of("data", Map.of(
                    "type", "controller_output", "payload", Map.of("type", "all_tasks_processed")))),
                    null, false, true);
        }

        private void addArtifact(String artifactId, List<Map<String, Object>> parts,
                Map<String, Object> agentEvent, boolean append, boolean last) {
            ObjectNode result = JSON.createObjectNode();
            ObjectNode update = result.putObject("artifactUpdate");
            update.put("taskId", id).put("contextId", contextId).put("append", append).put("lastChunk", last);
            ObjectNode artifact = update.putObject("artifact");
            artifact.put("artifactId", artifactId);
            artifact.set("parts", JSON.valueToTree(parts));
            if (agentEvent != null) {
                artifact.putObject("metadata").set("agentEvent", JSON.valueToTree(agentEvent));
            }
            addResult(result);
        }

        private void addStatus(String value, boolean terminal, Map<String, Object> interrupt) {
            ObjectNode result = JSON.createObjectNode();
            ObjectNode update = result.putObject("statusUpdate");
            update.put("taskId", id).put("contextId", contextId).put("final", terminal);
            ObjectNode status = update.putObject("status").put("state", value);
            if (interrupt != null) {
                status.putObject("message").putObject("metadata").set("_interrupt", JSON.valueToTree(interrupt));
            }
            addResult(result);
        }

        private static Map<String, Object> interrupt(String kind, String toolCallId, String toolName,
                String prompt, Map<String, Object> arguments) {
            Map<String, Object> context = new java.util.LinkedHashMap<>();
            context.put("_interrupt_kind", kind);
            if (arguments != null) {
                context.put("arguments", arguments);
            }
            Map<String, Object> value = new java.util.LinkedHashMap<>();
            value.put("toolCallId", toolCallId);
            value.put("context", context);
            if (toolName != null) {
                value.put("toolName", toolName);
            }
            if (prompt != null) {
                value.put("message", prompt);
            }
            return value;
        }

        private void addResult(ObjectNode result) {
            ObjectNode root = JSON.createObjectNode();
            root.put("jsonrpc", "2.0").put("id", "mock-runtime");
            root.set("result", result);
            frames.add(new Frame(frames.size() + 1, root.toString()));
        }

        private JsonNode taskNode() {
            ObjectNode task = JSON.createObjectNode();
            task.put("id", id).put("contextId", contextId);
            ObjectNode statusNode = task.putObject("status").put("state", state);
            if ("TASK_STATE_INPUT_REQUIRED".equals(state) && "input-linear".equals(scenario)) {
                statusNode.putObject("message").putObject("metadata").set("_interrupt", JSON.valueToTree(
                        interrupt("user_input", "input-" + id, null, "Please provide the account suffix.", null)));
            }
            ArrayNode artifacts = task.putArray("artifacts");
            artifacts.addObject().put("artifactId", "root-final").putArray("parts")
                    .addObject().put("text", output);
            return task;
        }

        private Map<String, Object> summary() {
            return Map.of("taskId", id, "contextId", contextId, "scenario", scenario,
                    "state", state, "frames", frames.size(), "subscribeAttempts", subscribeAttempts.get(),
                    "createdAt", createdAt.toString());
        }

        private Instant createdAt() {
            return createdAt;
        }
    }
}
