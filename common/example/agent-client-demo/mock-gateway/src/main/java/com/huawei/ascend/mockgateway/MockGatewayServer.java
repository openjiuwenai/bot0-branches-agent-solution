/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.mockgateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 模拟 gateway + runtime 的 A2A 入口（<b>验证用，非 SDK 交付</b>）。
 *
 * <p>纯 JDK 内置 {@link HttpServer} 实现，暴露单一端点 {@code POST /a2a}（A2A JSON-RPC 2.0 over HTTP + SSE）：
 * <ul>
 * <li>{@code SendStreamingMessage} —— SSE 事件流（创建调用，对应 STREAMING）。</li>
 * <li>{@code SendMessage} —— 单条 JSON 响应（本地工具结果 / 用户输入续跑，Feat-Func-011 §5.9.3）。</li>
 * </ul>
 *
 * <p>治理对齐（Feat-Func-011 §4.9）：每个请求强制 Bearer 鉴权（缺失 {@code AUTH_MISSING} / 非法 {@code AUTH_INVALID}，
 * 均 401）；{@code agentId} 可选，显式给出时不得为空串（否则 400 {@code VALIDATION_AGENT_ID}）；
 * 创建请求按 {@code message.messageId} 幂等去重。
 *
 * <p>它按 Feat-Func-009 的语义驱动 client 工具多轮：读取 {@code params.metadata.clientTools}（即 ToolView），
 * 按序对每个工具通过 {@code _interrupt} 请求一次；收到续跑结果后推进到下一个，全部完成则结束。
 * 为验证客户端"最多执行一次 / 最多续跑一次"，流式路径会对首个工具故意重复投递一次 INPUT_REQUIRED。
 *
 * @since 2026-07-27
 */
public final class MockGatewayServer {
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(MockGatewayServer.class.getName());

    /**
     * 网关工作线程的 ThreadFactory：基于默认工厂包装出 daemon + 未捕获异常处理 + 自定义命名。
     */
    private static final java.util.concurrent.ThreadFactory WORKER_FACTORY = r -> {
        Thread t = java.util.concurrent.Executors.defaultThreadFactory().newThread(r);
        t.setName("mock-gateway");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) -> {
            // best-effort：网关工作线程未捕获异常不中断服务。
        });
        return t;
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, TaskSim> tasks = new ConcurrentHashMap<>();

    // G4 幂等：创建请求按 message.messageId 去重，重复请求复用同一 Task。
    private final ConcurrentMap<String, String> messageIdToTask = new ConcurrentHashMap<>();
    private final int requestedPort;
    private HttpServer server;

    public MockGatewayServer(int port) {
        this.requestedPort = port;
    }

    /**
     * 启动 Mock 网关进程。
     *
     * @param args 命令行参数，第一个为端口号（可选）
     * @throws Exception 启动失败时抛出
     */
    public static void main(String[] args) throws Exception {
        int port = (args.length > 0) ? Integer.parseInt(args[0])
                : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        MockGatewayServer server = new MockGatewayServer(port);
        int bound = server.start();
        LOG.info("[mock-gateway] A2A endpoint listening on http://127.0.0.1:" + bound + "/a2a");
        // shutdown hook 通过 ThreadFactory 创建，避免直接的 new Thread（G.CON.12）
        Thread shutdownHook = WORKER_FACTORY.newThread(server::stop);
        shutdownHook.setName("mock-gateway-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        Thread.currentThread().join();
    }

    /**
     * 启动并返回实际绑定端口（传 0 时由系统分配，便于嵌入式验证）。
     *
     * @return 启动并返回实际绑定端口（传 0 时由系统分配，便于嵌入式验证）。
     * @throws IOException 若发生 IOException
     */
    public int start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
        server.setExecutor(new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(), WORKER_FACTORY));
        server.createContext("/a2a", this::handleA2a);
        server.createContext("/.well-known/agent-card.json", this::handleAgentCard);
        server.start();
        return server.getAddress().getPort();
    }

    /**
     * 停止网关服务。
     */
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---------- HTTP handlers ----------

    private void handleAgentCard(HttpExchange ex) throws IOException {
        ObjectNode card = mapper.createObjectNode();
        card.put("name", "mock-agent");
        card.put("description", "mock gateway+runtime for agent-client verification");
        card.put("url", "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a");
        card.putArray("capabilities").add("streaming").add("clientTools");
        writeJson(ex, 200, card);
    }

    private void handleA2a(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        // G1 强制鉴权（Feat-Func-011 §4.9）：每个 HTTP 请求必须携带 Authorization: Bearer <token>。
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || auth.isBlank()) {
            writeGovernanceError(ex, 401, "AUTH_MISSING", "missing Authorization header");
            return;
        }
        if (!auth.regionMatches(true, 0, "Bearer ", 0, 7) || auth.substring(7).isBlank()) {
            writeGovernanceError(ex, 401, "AUTH_INVALID", "Authorization must be a non-empty Bearer token");
            return;
        }
        JsonNode req;
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            req = mapper.readTree(body);
        } catch (IOException e) {
            writeGovernanceError(ex, 400, "VALIDATION_BODY", "malformed JSON body");
            return;
        }
        String id = req.path("id").asText(null);
        String method = req.path("method").asText("");
        JsonNode params = req.path("params");
        try {
            switch (method) {
                case "SendMessage" -> handleMessage(ex, id, params, false);
                case "SendStreamingMessage" -> handleMessage(ex, id, params, true);
                default -> writeJson(ex, 200, rpcError(id, -32601, "method not found: " + method));
            }
        } catch (IOException | RuntimeException e) {
            writeJson(ex, 200, rpcError(id, -32603, "internal error: " + e.getMessage()));
        }
    }

    private void handleMessage(HttpExchange ex, String rpcId, JsonNode params, boolean streaming)
            throws IOException {
        JsonNode message = params.path("message");
        String contextId = message.path("contextId").asText(null);
        String taskId = message.path("taskId").asText(null);
        String messageId = message.path("messageId").asText(null);
        JsonNode metadata = params.path("metadata");

        if (taskId == null || taskId.isEmpty()) {
            // G3 校验（Feat-Func-011 §4.9）：agentId 可选，但若显式给出则不得为空串。
            if (metadata.has("agentId") && metadata.path("agentId").asText("").isBlank()) {
                writeGovernanceError(ex, 400, "VALIDATION_AGENT_ID", "agentId must not be empty when present");
                return;
            }
            // G4 幂等：同一 messageId 的重复创建复用既有 Task，不新建。
            if (messageId != null && messageIdToTask.containsKey(messageId)) {
                TaskSim existing = tasks.get(messageIdToTask.get(messageId));
                if (existing == null) {
                    // 索引残留但 Task 已丢失：落到下方新建分支重建。
                } else {
                    replayExisting(ex, rpcId, existing, streaming);
                    return;
                }
            }
            createAndRespond(ex, rpcId, params, message, streaming);
            return;
        }

        TaskSim task = tasks.get(taskId);
        if (task == null) {
            writeJson(ex, 200, rpcError(rpcId, -32001, "unknown task " + taskId));
            return;
        }

        synchronized (task) {
            String submittedToolCallId = extractToolCallId(message).orElse(null);
            advanceOnResume(task, submittedToolCallId);
        }
        if (streaming) {
            streamCurrent(ex, rpcId, task, false);
        } else {
            writeJson(ex, 200, rpcResult(rpcId, buildResult(task, "task")));
        }
    }

    private void createAndRespond(HttpExchange ex, String rpcId, JsonNode params, JsonNode message,
                                  boolean streaming) throws IOException {
        String contextId = message.path("contextId").asText(null);
        JsonNode metadata = params.path("metadata");
        String messageId = message.path("messageId").asText(null);
        TaskSim task = createTask(contextId, message, metadata);
        if (messageId != null) {
            messageIdToTask.put(messageId, task.taskId);
        }
        if (streaming) {
            streamCurrent(ex, rpcId, task, true);
        } else {
            writeJson(ex, 200, rpcResult(rpcId, buildResult(task, "task")));
        }
    }

    /**
     * 幂等命中时回放既有 Task：流式则推送当前快照，否则返回单条结果。
     *
     * @param ex 异常
     * @param rpcId JSON-RPC 请求标识
     * @param existing 已存在的任务模拟
     * @param streaming 是否流式
     * @throws IOException 若发生 IOException
     */
    private void replayExisting(HttpExchange ex, String rpcId, TaskSim existing, boolean streaming)
            throws IOException {
        if (streaming) {
            streamCurrent(ex, rpcId, existing, true);
        } else {
            writeJson(ex, 200, rpcResult(rpcId, buildResult(existing, "task")));
        }
    }
    // ---------- task lifecycle ----------
    /**
     * createTask。
     *
     * @param contextId String
     * @param message JsonNode
     * @param metadata JsonNode
     * @return createTask
     */

    private TaskSim createTask(String contextId, JsonNode message, JsonNode metadata) {
        TaskSim task = new TaskSim();
        task.taskId = "task-" + UUID.randomUUID();
        task.contextId = contextId;
        JsonNode clientTools = metadata.path("clientTools");
        if (clientTools.isArray()) {
            for (JsonNode t : clientTools) {
                String name = t.path("name").asText();
                task.toolNames.add(name);
                task.toolSchemas.put(name, t.path("inputSchema"));
            }
        }
        String input = extractText(message).orElse(null);
        tasks.put(task.taskId, task);

        if (!task.toolNames.isEmpty()) {
            task.scenario = Scenario.CLIENT_TOOLS;
            requestToolRound(task);
        } else if (input != null && input.startsWith("NEEDS_USER_INPUT")) {
            task.scenario = Scenario.USER_INPUT;
            task.state = State.INPUT_REQUIRED;
            task.pending = Pending.userInput("please provide additional input");
        } else {
            task.scenario = Scenario.IMMEDIATE;
            task.state = State.COMPLETED;
            task.outputText = "echo: " + (input != null ? input : "");
        }
        return task;
    }

    private void requestToolRound(TaskSim task) {
        String name = task.toolNames.get(task.round);
        String toolCallId = "call-" + task.taskId + "-" + task.round;
        task.state = State.INPUT_REQUIRED;
        task.pending = Pending.clientTool(toolCallId, name, buildArgs(task.toolSchemas.get(name)));
    }

    private void advanceOnResume(TaskSim task, String submittedToolCallId) {
        if (task.state != State.INPUT_REQUIRED || task.pending == null) {
            return; // 幂等：非等待态的重复续传不推进
        }
        if (task.pending.toolCallId != null && submittedToolCallId != null
                && !task.pending.toolCallId.equals(submittedToolCallId)) {
            return; // 幂等：陈旧/重复 toolCallId 的续传不推进
        }
        if (task.scenario == Scenario.CLIENT_TOOLS) {
            task.round++;
            if (task.round < task.toolNames.size()) {
                requestToolRound(task);
            } else {
                task.state = State.COMPLETED;
                task.pending = null;
                task.outputText = "completed after " + task.toolNames.size() + " client tool round(s)";
            }
        } else if (task.scenario == Scenario.USER_INPUT) {
            task.state = State.COMPLETED;
            task.pending = null;
            task.outputText = "completed with user-provided input";
        } else {
            // 其他场景：无需推进（如 PLAIN 直接 COMPLETED）。
        }
    }

    // ---------- SSE streaming ----------

    private void streamCurrent(HttpExchange ex, String rpcId, TaskSim task, boolean isCreate)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            if (isCreate) {
                // 首帧交付 taskId
                sendFrame(os, rpcId, buildStatus(task, State.WORKING, false));
            }
            switch (task.state) {
                case INPUT_REQUIRED -> {
                    sendFrame(os, rpcId, buildResult(task, "status-update"));
                    if (isCreate && task.scenario == Scenario.CLIENT_TOOLS && task.round == 0) {
                        // 故意重复投递一次，验证客户端去重。
                        sendFrame(os, rpcId, buildResult(task, "status-update"));
                    }
                    // INPUT_REQUIRED 后关闭当前 SSE 队列（Feat-Func-009 语义），等待客户端续传。
                }
                case COMPLETED -> {
                    if (task.scenario == Scenario.IMMEDIATE) {
                        sendFrame(os, rpcId, buildArtifact(task, task.outputText));
                    }
                    sendFrame(os, rpcId, buildResult(task, "status-update"));
                }

                default -> sendFrame(os, rpcId, buildResult(task, "status-update"));
            }
        }
    }

    private void sendFrame(OutputStream os, String rpcId, ObjectNode result) throws IOException {
        // SSE 帧格式：event: jsonrpc + data: <json>（对齐 feat-011 §4.9.3 GW-2 / 006 §3.5 ②）。
        String payload = "event: jsonrpc\ndata: " + write(rpcResult(rpcId, result)) + "\n\n";
        os.write(payload.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    // ---------- result builders ----------

    /**
     * buildResult。
     *
     * @param task TaskSim
     * @param kind String
     * @return buildResult
     */

    private ObjectNode buildResult(TaskSim task, String kind) {
        ObjectNode r = mapper.createObjectNode();
        r.put("kind", kind);
        if ("task".equals(kind)) {
            r.put("id", task.taskId);
        } else {
            r.put("taskId", task.taskId);
        }
        if (task.contextId != null) {
            r.put("contextId", task.contextId);
        }
        ObjectNode status = r.putObject("status");
        status.put("state", a2aState(task.state));
        // message 层：COMPLETED 携带输出文本；INPUT_REQUIRED 携带 _interrupt（对齐 009 §6.3 / 006 §3.5 ② / 007 §3.5 ②）。
        ObjectNode message = status.putObject("message");
        message.put("role", "agent");
        if (task.state == State.COMPLETED && task.outputText != null) {
            message.putArray("parts").addObject().put("kind", "text").put("text", task.outputText);
        }
        ObjectNode msgMeta = message.putObject("metadata");
        if (task.state == State.INPUT_REQUIRED && task.pending != null) {
            buildInterrupt(msgMeta, task.pending);
        }
        if ("status-update".equals(kind)) {
            r.put("final", isTerminal(task.state));
        }
        ObjectNode meta = r.putObject("metadata");
        if (task.errorCode != null) {
            meta.put("errorCode", task.errorCode);
        }
        return r;
    }

    private void buildInterrupt(ObjectNode msgMeta, Pending pending) {
        // _interrupt 权威路径：status.message.metadata._interrupt；
        // _interrupt_kind / arguments 嵌套在 context 下；顶层保留 toolCallId / toolName / message。
        ObjectNode it = msgMeta.putObject("_interrupt");
        it.put("type", "__interaction__");
        it.put("index", 0);
        it.put("toolCallId", pending.toolCallId);
        String interruptMessage = (pending.userInput)
                ? (pending.prompt != null ? pending.prompt : "user input required")
                : "Client tool invocation required: " + pending.toolName;
        it.put("message", interruptMessage);
        ObjectNode ctx = it.putObject("context");
        ctx.put("_interrupt_kind", pending.userInput ? "user_input" : "client_tool");
        if (pending.userInput) {
            if (pending.prompt != null) {
                ctx.put("prompt", pending.prompt);
            }
        } else {
            ctx.put("toolName", pending.toolName);
            ctx.set("arguments", pending.arguments);
            it.put("toolName", pending.toolName);
            it.put("deadlineMs", 30000);
        }
    }

    /**
     * buildStatus。
     *
     * @param task TaskSim
     * @param state State
     * @param finalFlag boolean
     * @return buildStatus
     */

    private ObjectNode buildStatus(TaskSim task, State state, boolean finalFlag) {
        ObjectNode r = mapper.createObjectNode();
        r.put("kind", "status-update");
        r.put("taskId", task.taskId);
        if (task.contextId != null) {
            r.put("contextId", task.contextId);
        }
        r.putObject("status").put("state", a2aState(state));
        r.put("final", finalFlag);
        return r;
    }

    /**
     * buildArtifact。
     *
     * @param task TaskSim
     * @param text String
     * @return buildArtifact
     */

    private ObjectNode buildArtifact(TaskSim task, String text) {
        ObjectNode r = mapper.createObjectNode();
        r.put("kind", "artifact-update");
        r.put("taskId", task.taskId);
        ObjectNode artifact = r.putObject("artifact");
        artifact.putArray("parts").addObject().put("kind", "text").put("text", text);
        return r;
    }

    /**
     * buildArgs。
     *
     * @param inputSchema JsonNode
     * @return buildArgs
     */

    private ObjectNode buildArgs(JsonNode inputSchema) {
        ObjectNode args = mapper.createObjectNode();
        if (inputSchema != null && inputSchema.path("required").isArray()) {
            for (JsonNode k : inputSchema.path("required")) {
                args.put(k.asText(), "mock-" + k.asText());
            }
        }
        return args;
    }

    // ---------- JSON-RPC helpers ----------

    /**
     * rpcResult。
     *
     * @param id String
     * @param result ObjectNode
     * @return rpcResult
     */

    private ObjectNode rpcResult(String id, ObjectNode result) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        if (id != null) {
            root.put("id", id);
        }
        root.set("result", result);
        return root;
    }

    /**
     * rpcError。
     *
     * @param id String
     * @param code int
     * @param message String
     * @return rpcError
     */

    private ObjectNode rpcError(String id, int code, String message) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        if (id != null) {
            root.put("id", id);
        }
        ObjectNode err = root.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return root;
    }

    /**
     * 网关治理错误：以 HTTP 状态码 + {@code {code,message}} 响应体返回（Feat-Func-011 §4.9）。
     *
     * @param ex 异常
     * @param status HTTP 状态码
     * @param code 错误码
     * @param message 消息文本
     * @throws IOException 若发生 IOException
     */
    private void writeGovernanceError(HttpExchange ex, int status, String code, String message)
            throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("code", code);
        body.put("message", message);
        writeJson(ex, status, body);
    }

    private void writeJson(HttpExchange ex, int status, ObjectNode body) throws IOException {
        byte[] bytes = write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    /**
     * JSON 文本。
     *
     * @param node ObjectNode
     * @return JSON 文本
     */

    private String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    /**
     * extractText。
     *
     * @param message JsonNode
     * @return extractText
     */

    private static Optional<String> extractText(JsonNode message) {
        JsonNode parts = message.path("parts");
        if (parts.isArray()) {
            for (JsonNode p : parts) {
                if ("text".equals(p.path("kind").asText(""))) {
                    return Optional.of(p.path("text").asText(""));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * extractToolCallId。
     *
     * @param message JsonNode
     * @return extractToolCallId
     */

    private static Optional<String> extractToolCallId(JsonNode message) {
        JsonNode parts = message.path("parts");
        if (parts.isArray()) {
            for (JsonNode p : parts) {
                String id = p.path("metadata").path("toolCallId").asText(null);
                if (id != null && !id.isEmpty()) {
                    return Optional.of(id);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * a2aState。
     *
     * @param s State
     * @return a2aState
     */

    private static String a2aState(State s) {
        // 权威值为 TASK_STATE_* 大写带前缀（Feat-Func-009 §6.3 / 006 §3.3）。
        return switch (s) {
            case SUBMITTED -> "TASK_STATE_SUBMITTED";
            case WORKING -> "TASK_STATE_WORKING";
            case INPUT_REQUIRED -> "TASK_STATE_INPUT_REQUIRED";
            case COMPLETED -> "TASK_STATE_COMPLETED";
            case CANCELED -> "TASK_STATE_CANCELED";
            case FAILED -> "TASK_STATE_FAILED";
        };
    }

    /**
     * 布尔结果。
     *
     * @param s State
     * @return 布尔结果
     */

    private static boolean isTerminal(State s) {
        return s == State.COMPLETED || s == State.CANCELED || s == State.FAILED;
    }

    private enum State {SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, CANCELED, FAILED}

    private enum Scenario {CLIENT_TOOLS, USER_INPUT, IMMEDIATE}

    private static final class Pending {
        boolean userInput;
        String toolCallId;
        String toolName;
        String prompt;
        JsonNode arguments;

        /**
         * client_tool 类型 Pending。
         *
         * @param toolCallId String
         * @param toolName String
         * @param arguments JsonNode
         * @return client_tool 类型 Pending
         */

        static Pending clientTool(String toolCallId, String toolName, JsonNode arguments) {
            Pending p = new Pending();
            p.userInput = false;
            p.toolCallId = toolCallId;
            p.toolName = toolName;
            p.arguments = arguments;
            return p;
        }

        /**
         * user_input 类型 Pending。
         *
         * @param prompt String
         * @return user_input 类型 Pending
         */

        static Pending userInput(String prompt) {
            Pending p = new Pending();
            p.userInput = true;
            p.toolCallId = "userinput-" + UUID.randomUUID();
            p.prompt = prompt;
            return p;
        }
    }

    private static final class TaskSim {
        String taskId;
        String contextId;
        Scenario scenario;
        volatile State state = State.SUBMITTED;
        Pending pending;
        String outputText;
        String errorCode;
        int round = 0;
        final List<String> toolNames = new ArrayList<>();
        final Map<String, JsonNode> toolSchemas = new LinkedHashMap<>();
    }
}
