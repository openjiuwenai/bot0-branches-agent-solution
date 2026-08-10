/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.mockgateway;

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
 * <li>{@code SendMessage} —— 单条 JSON 响应（创建 BLOCKING/ASYNC 调用、以及本地工具结果 / 用户输入续跑，
 * Feat-Func-011 §5.9.3）。</li>
 * <li>{@code GetTask} —— 单条 JSON 响应（状态查询，参数为 {@code params.id}）。</li>
 * </ul>
 *
 * <p>北向方法白名单只含上述三者；其余方法（{@code CancelTask} / {@code SubscribeToTask}）
 * 按治理语义返回 {@code 400 VALIDATION_METHOD}，与真实网关 v0730 的开放面一致。
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

    /** SSE 帧的行分隔符（协议要求字面量 LF）。 */
    private static final String LF = String.valueOf((char) 10);

    /**
     * 网关工作线程的 ThreadFactory：基于默认工厂包装出 daemon + 未捕获异常处理 + 自定义命名。
     */
    private static final java.util.concurrent.ThreadFactory WORKER_FACTORY = r -> {
        Thread t = java.util.concurrent.Executors.defaultThreadFactory().newThread(r);
        t.setName("mock-gateway");
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) -> {
            // best-effort：网关工作线程未捕获异常不中断服务，仅记录日志。
            LOG.log(java.util.logging.Level.WARNING,
                    "uncaught exception in mock-gateway worker " + thread.getName(), ex);
        });
        return t;
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentMap<String, TaskSim> tasks = new ConcurrentHashMap<>();

    // G4 幂等：创建请求按 message.messageId 去重，重复请求复用同一 Task。
    private final ConcurrentMap<String, String> messageIdToTask = new ConcurrentHashMap<>();
    private final int requestedPort;
    private HttpServer server;

    /**
     * 构造 Mock 网关实例。
     *
     * @param port 请求监听端口；传 0 由系统分配
     */
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
        LOG.log(java.util.logging.Level.INFO,
                "[mock-gateway] A2A endpoint listening on http://127.0.0.1:{0}/a2a", bound);
        // shutdown hook 通过 ThreadFactory 创建，避免直接的 new Thread（G.CON.12）
        Thread shutdownHook = WORKER_FACTORY.newThread(server::stop);
        shutdownHook.setName("mock-gateway-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
        Thread.currentThread().join();
    }

    /**
     * 启动并返回实际绑定端口（传 0 时由系统分配，便于嵌入式验证）。
     *
     * @return 实际绑定端口
     * @throws IOException 端口绑定失败时抛出
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
                case "GetTask" -> handleGetTask(ex, id, params);
                // 未在北向白名单内的方法（CancelTask / SubscribeToTask 等）按网关治理语义拒绝：
                // HTTP 400 + VALIDATION_METHOD，而不是 JSON-RPC -32601。
                default -> writeGovernanceError(ex, 400, "VALIDATION_METHOD",
                        "method not allowed on northbound: " + method);
            }
        } catch (IOException | IllegalStateException e) {
            writeJson(ex, 200, rpcError(id, -32603, "internal error: " + e.getMessage()));
        }
    }

    /**
     * 状态查询（{@code GetTask}）：返回该 Task 当前的权威快照。
     *
     * <p>参数位置与 agent-runtime-java 契约一致：{@code params.id}（标准 A2A {@code TaskQueryParams.id}）。
     * 客户端用它做显式状态查询、ASYNC 观察和断连后确认真实进展；严格 unary BLOCKING 不自动查询。
     *
     * @param ex HTTP 交换
     * @param rpcId JSON-RPC 请求标识
     * @param params 请求参数
     * @throws IOException 写响应失败时抛出
     */
    private void handleGetTask(HttpExchange ex, String rpcId, JsonNode params) throws IOException {
        String taskId = params.path("id").asText(null);
        if (taskId == null || taskId.isBlank()) {
            writeGovernanceError(ex, 400, "VALIDATION_TASK_ID", "id is required for GetTask");
            return;
        }
        TaskSim task = tasks.get(taskId);
        if (task == null) {
            writeJson(ex, 200, rpcError(rpcId, -32001, "unknown task " + taskId));
            return;
        }
        writeJson(ex, 200, rpcResult(rpcId, buildResult(task, "task")));
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
                // 索引残留但 Task 已丢失：落到下方新建分支重建。
                if (existing != null) {
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
     * @param ex HTTP 交换对象
     * @param rpcId JSON-RPC 请求标识
     * @param existing 已存在的任务模拟
     * @param streaming 是否流式
     * @throws IOException 写响应失败时抛出
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
     * 创建并初始化一个 Task 模拟。
     *
     * @param contextId 会话标识
     * @param message 请求消息
     * @param metadata 请求元数据
     * @return 新建的任务模拟
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

        // 断连模拟场景优先判定：触发词与 verification-app 当前输入文本对齐。
        // S8 用 "stream hello"，S9 用 "stream hello again"（两者唯一，不与其他场景冲突）。
        // 真栈下这两个输入是普通 COMPLETED；mock 保留断连语义以验证真栈无法覆盖的断连恢复路径。
        if (input != null && input.equals("stream hello")) {
            // 流中断但服务端其实已把任务跑完：客户端应能靠 GetTask 把"不确定"变回"确定"。
            task.scenario = Scenario.DROP_THEN_COMPLETE;
            task.state = State.WORKING;
            task.outputText = "recovered after mid-stream drop";
        } else if (input != null && input.equals("stream hello again")) {
            // 流中断且服务端仍在跑：客户端查询也无法确定，应投递"进展不确定"而非判失败或悬挂。
            task.scenario = Scenario.DROP_STAYS_WORKING;
            task.state = State.WORKING;
        } else if (!task.toolNames.isEmpty()) {
            task.scenario = Scenario.CLIENT_TOOLS;
            requestToolRound(task);
        } else if (input != null && input.equals("Please calculate 1+1 through Agent B.")) {
            // S3：对齐真栈，Agent B 的 calc 工具会触发 INPUT_REQUIRED，客户端续轮回复 "ok" 后完成。
            task.scenario = Scenario.USER_INPUT;
            task.state = State.INPUT_REQUIRED;
            task.pending = Pending.userInput("please provide input for Agent B's calc tool");
        } else {
            task.scenario = Scenario.IMMEDIATE;
            task.state = State.COMPLETED;
            // 回显元信息，供验证侧确认其确实上了 wire（FEAT-011 §4.9 / FEAT-006 §3 业务上下文与凭证传递）。
            // 仅在存在时追加，未使用该能力的调用方输出与既有链路一致。
            String agentId = metadata.path("agentId").asText(null);
            String traceId = metadata.path("attributes").path("traceId").asText(null);
            task.outputText = "echo: " + (input != null ? input : "")
                    + (agentId != null && !agentId.isBlank() ? " [agent=" + agentId + "]" : "")
                    + (traceId != null && !traceId.isBlank() ? " [trace=" + traceId + "]" : "");
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
            return;
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
            if (task.scenario == Scenario.DROP_THEN_COMPLETE
                    || task.scenario == Scenario.DROP_STAYS_WORKING) {
                // 模拟非预期中断：已投出 taskId，但不再下发任何终态/等待态帧就关闭流。
                // DROP_THEN_COMPLETE 在关流前把任务推到终态，使随后的 GetTask 能给出确定结果。
                if (task.scenario == Scenario.DROP_THEN_COMPLETE) {
                    task.state = State.COMPLETED;
                }
                return;
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
        String payload = "event: jsonrpc" + LF + "data: " + write(rpcResult(rpcId, result)) + LF + LF;
        os.write(payload.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    // ---------- result builders ----------

    /**
     * 构造响应结果节点。
     *
     * <p>对齐 agent-runtime-java 标准 A2A 形态（documents/zh/2.开发指南/对话接口输入与输出.md）：
     * <ul>
     *   <li>{@code kind="task"} → {@code result.task}（完整 Task 对象，{@code id}/{@code contextId}/
     *       {@code status}/{@code artifacts}/{@code history}）</li>
     *   <li>{@code kind="status-update"} → {@code result.statusUpdate}（{@code taskId}/
     *       {@code contextId}/{@code status}/{@code final}）</li>
     * </ul>
     *
     * @param task 任务模拟
     * @param kind 结果类型
     * @return 结果节点
     */
    private ObjectNode buildResult(TaskSim task, String kind) {
        ObjectNode r = mapper.createObjectNode();
        // 构建 status 子对象（COMPLETED 携带输出文本；INPUT_REQUIRED 携带 _interrupt）。
        ObjectNode status = mapper.createObjectNode();
        status.put("state", a2aState(task.state));
        ObjectNode message = status.putObject("message");
        message.put("role", "agent");
        if (task.state == State.COMPLETED && task.outputText != null) {
            // 标准 A2A TextPart = {text}，不写 kind 字段。
            message.putArray("parts").addObject().put("text", task.outputText);
        }
        ObjectNode msgMeta = message.putObject("metadata");
        if (task.state == State.INPUT_REQUIRED && task.pending != null) {
            buildInterrupt(msgMeta, task.pending);
        }

        if ("task".equals(kind)) {
            // 非流式：result.task = { id, contextId, status, artifacts, history }
            ObjectNode taskNode = r.putObject("task");
            taskNode.put("id", task.taskId);
            if (task.contextId != null) {
                taskNode.put("contextId", task.contextId);
            }
            taskNode.set("status", status);
            taskNode.putArray("artifacts");
            taskNode.putArray("history");
            if (task.errorCode != null) {
                taskNode.putObject("metadata").put("errorCode", task.errorCode);
            }
        } else {
            // 流式：result.statusUpdate = { taskId, contextId, status, final }
            ObjectNode statusUpdate = r.putObject("statusUpdate");
            statusUpdate.put("taskId", task.taskId);
            if (task.contextId != null) {
                statusUpdate.put("contextId", task.contextId);
            }
            statusUpdate.set("status", status);
            statusUpdate.put("final", isTerminal(task.state));
            if (task.errorCode != null) {
                statusUpdate.putObject("metadata").put("errorCode", task.errorCode);
            }
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
     * 构造状态更新节点（首帧 WORKING 投递用）。
     *
     * <p>对齐标准 A2A：{@code result.statusUpdate = { taskId, contextId, status, final }}。
     *
     * @param task 任务模拟
     * @param state 任务状态
     * @param finalFlag 是否终态
     * @return 状态更新节点
     */
    private ObjectNode buildStatus(TaskSim task, State state, boolean finalFlag) {
        ObjectNode r = mapper.createObjectNode();
        ObjectNode statusUpdate = r.putObject("statusUpdate");
        statusUpdate.put("taskId", task.taskId);
        if (task.contextId != null) {
            statusUpdate.put("contextId", task.contextId);
        }
        statusUpdate.putObject("status").put("state", a2aState(state));
        statusUpdate.put("final", finalFlag);
        return r;
    }

    /**
     * 构造产物更新节点。
     *
     * <p>对齐标准 A2A：{@code result.artifactUpdate = { taskId, contextId, artifact:{ artifactId, parts:[{text}] }}}。
     *
     * @param task 任务模拟
     * @param text 产物文本
     * @return 产物更新节点
     */
    private ObjectNode buildArtifact(TaskSim task, String text) {
        ObjectNode r = mapper.createObjectNode();
        ObjectNode artifactUpdate = r.putObject("artifactUpdate");
        artifactUpdate.put("taskId", task.taskId);
        if (task.contextId != null) {
            artifactUpdate.put("contextId", task.contextId);
        }
        ObjectNode artifact = artifactUpdate.putObject("artifact");
        artifact.put("artifactId", "artifact-" + task.taskId);
        // 标准 A2A TextPart = {text}，不写 kind 字段。
        artifact.putArray("parts").addObject().put("text", text);
        return r;
    }

    /**
     * 按 inputSchema 的 required 字段构造 mock 参数。
     *
     * @param inputSchema 输入 schema
     * @return mock 参数节点
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
     * 构造 JSON-RPC 成功响应。
     *
     * @param id 请求标识
     * @param result 结果节点
     * @return 响应根节点
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
     * 构造 JSON-RPC 错误响应。
     *
     * @param id 请求标识
     * @param code 错误码
     * @param message 错误信息
     * @return 错误响应根节点
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
     * @param ex HTTP 交换对象
     * @param status HTTP 状态码
     * @param code 错误码
     * @param message 消息文本
     * @throws IOException 写响应失败时抛出
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
     * 序列化为 JSON 文本。
     *
     * @param node 对象节点
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
     * 从消息 parts 中提取首段文本。
     *
     * <p>标准 A2A Part 按字段名联合区分：TextPart = {text}。兼容旧形态 {kind:"text", text:"..."}。
     *
     * @param message 消息节点
     * @return 文本内容
     */
    private static Optional<String> extractText(JsonNode message) {
        JsonNode parts = message.path("parts");
        if (parts.isArray()) {
            for (JsonNode p : parts) {
                if (p.has("text")) {
                    return Optional.of(p.path("text").asText(""));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 从消息 parts 中提取工具调用标识。
     *
     * @param message 消息节点
     * @return 工具调用标识
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
     * 把内部状态枚举映射为 A2A wire 状态字符串。
     *
     * @param s 内部状态
     * @return A2A 状态字符串
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
     * 判断状态是否为终态。
     *
     * @param s 内部状态
     * @return 终态返回 true
     */
    private static boolean isTerminal(State s) {
        return s == State.COMPLETED || s == State.CANCELED || s == State.FAILED;
    }

    private enum State {SUBMITTED, WORKING, INPUT_REQUIRED, COMPLETED, CANCELED, FAILED}

    private enum Scenario {
        CLIENT_TOOLS, USER_INPUT, IMMEDIATE,
        /** 非终态下中断 SSE，但服务端任务随后到达 COMPLETED（可被 GetTask 查到）。 */
        DROP_THEN_COMPLETE,
        /** 非终态下中断 SSE，且服务端任务一直停在 WORKING（查询也无法确定）。 */
        DROP_STAYS_WORKING
    }

    private static final class Pending {
        boolean userInput;
        String toolCallId;
        String toolName;
        String prompt;
        JsonNode arguments;

        /**
         * client_tool 类型 Pending。
         *
         * @param toolCallId 工具调用标识
         * @param toolName 工具名
         * @param arguments 工具参数
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
         * @param prompt 提示文本
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
