/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.InvocationMode;
import com.openjiuwen.client.api.TaskState;
import com.openjiuwen.client.transport.spi.ToolWireSpec;
import com.openjiuwen.client.transport.spi.TransportProvider;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A2A JSON-RPC 2.0 报文的编解码（仅在 transport 内部使用 Jackson）。
 *
 * <p>请求侧：把中立指令映射为 {@code SendStreamingMessage}（创建/流式续跑）与 {@code SendMessage}（unary 创建/续跑）；
 * wire method 由首轮 mode 决定并沿续跑继承。unary 返回时机由 {@code params.configuration.returnImmediately} 表达。
 * 业务标识到 wire 字段的映射：{@code conversationId → message.contextId}、
 * {@code invocationId/idempotencyKey → message.messageId}、ToolView → {@code params.metadata.clientTools}、
 * 可选 {@code agentId → params.metadata.agentId}。
 *
 * <p>响应侧：把 Task / TaskStatusUpdateEvent / TaskArtifactUpdateEvent 解析为中立 {@link Frame}，
 * 其中 client 工具调用意图来自 {@code metadata._interrupt}（对齐 runtime Feat-Func-009）。
 */
final class A2aJsonCodec {
    private final ObjectMapper mapper;

    A2aJsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * ObjectMapper。
     *
     * @return ObjectMapper
     */

    ObjectMapper mapper() {
        return mapper;
    }

    // ---------- 请求构建 ----------

    /**
     * 新请求 ObjectNode。
     *
     * @param method String
     * @return 新请求 ObjectNode
     */

    ObjectNode newRequest(String method) {
        ObjectNode root = mapper.createObjectNode();
        root.put("jsonrpc", "2.0");
        root.put("id", UUID.randomUUID().toString());
        root.put("method", method);
        return root;
    }

    /**
     * create 请求。
     *
     * @param cmd TransportProvider.CreateCommand
     * @return create 请求
     */

    ObjectNode buildCreate(TransportProvider.CreateCommand cmd) {
        // STREAMING 用方法名表达；BLOCKING / ASYNC 共用 SendMessage，由 configuration 区分返回时机。
        String method = (cmd.mode() == InvocationMode.STREAMING)
                ? "SendStreamingMessage" : "SendMessage";
        ObjectNode root = newRequest(method);
        ObjectNode params = root.putObject("params");
        if (cmd.mode() != InvocationMode.STREAMING) {
            fillReturnImmediately(params, cmd.mode());
        }
        ObjectNode message = params.putObject("message");
        message.put("role", "ROLE_USER");
        // invocationId / idempotencyKey → message.messageId（网关据此去重，Feat-Func-011 §4.9 AC-4）。
        message.put("messageId", cmd.idempotencyKey() != null ? cmd.idempotencyKey() : cmd.invocationId());
        message.put("contextId", cmd.conversationId());
        if (cmd.relatedTaskRef() != null) {
            message.put("taskId", cmd.relatedTaskRef());
        }
        ArrayNode parts = message.putArray("parts");
        ObjectNode textPart = parts.addObject();
        // 标准 A2A Part 按字段名联合区分（TextPart = {text}），不写 kind 字段（对齐 agent-runtime-java）。
        textPart.put("text", cmd.input());
        fillMetadata(params, cmd.agentId(), cmd.clientTools(), cmd.attributes());
        return root;
    }

    /**
     * resume 请求。
     *
     * @param cmd TransportProvider.ResumeCommand
     * @return resume 请求
     */

    ObjectNode buildResume(TransportProvider.ResumeCommand cmd) {
        // 续跑 method 沿用首轮 mode（FEAT-006 §47）：STREAMING 走 SendStreamingMessage（SSE），
        // BLOCKING / ASYNC 走 unary SendMessage（由 configuration.returnImmediately 表达返回时机）。
        String method = (cmd.mode() == InvocationMode.STREAMING)
                ? "SendStreamingMessage" : "SendMessage";
        ObjectNode root = newRequest(method);
        ObjectNode params = root.putObject("params");
        // unary 才写 configuration.returnImmediately；流式 method 本身即流式返回，无此语义。
        if (cmd.mode() != InvocationMode.STREAMING) {
            fillReturnImmediately(params, cmd.mode());
        }
        ObjectNode message = params.putObject("message");
        message.put("role", "ROLE_USER");
        // 每次续跑用新的 messageId；taskId 关联既有 Task。toolCallId 在多并行工具场景下写入 part.metadata（见下）。
        message.put("messageId", cmd.messageId());
        message.put("taskId", cmd.taskRef());
        // 续跑稳定带 contextId（=原 conversationId，Feat-Func-006 §3.5 ③④ / feat-011 §4.9 AC-5 / §6.9 GW-S4-4）。
        if (cmd.conversationId() != null && !cmd.conversationId().isEmpty()) {
            message.put("contextId", cmd.conversationId());
        }
        ArrayNode parts = message.putArray("parts");
        ObjectNode textPart = parts.addObject();
        // 标准 A2A Part 按字段名联合区分（TextPart = {text}），不写 kind 字段（对齐 agent-runtime-java）。
        textPart.put("text", cmd.observationText());
        // 多并行工具定向恢复：业务层填充 toolCallId 时写入 part.metadata.toolCallId（agent-runtime-java
        // 要求多工具场景必须为每个 part 指定 toolCallId，否则返回 REMOTE_TOOL_INPUT_TARGET_REQUIRED）。
        // 单一 pending 场景 toolCallId 为 null，保持现状由 runtime 自动关联。
        if (cmd.toolCallId() != null && !cmd.toolCallId().isBlank()) {
            ObjectNode partMeta = textPart.putObject("metadata");
            partMeta.put("toolCallId", cmd.toolCallId());
        }
        // 续跑不重复声明 clientTools（仅创建时声明），metadata 保持空对象以对齐 A2A 结构。
        params.putObject("metadata");
        return root;
    }

    /**
     * 在 unary A2A 请求上表达服务端返回时机。ASYNC 在受理后立即返回，其他模式等待本轮结果。
     *
     * @param params 请求参数节点；configuration 子对象会被追加到此节点下
     * @param mode 调用模式；决定 returnImmediately 的取值
     */
    private static void fillReturnImmediately(ObjectNode params, InvocationMode mode) {
        params.putObject("configuration")
                .put("returnImmediately", mode == InvocationMode.ASYNC);
    }

    private void fillMetadata(ObjectNode params, String agentId, List<ToolWireSpec> clientTools,
                             Map<String, String> attributes) {
        ObjectNode metadata = params.putObject("metadata");
        // agentId 可选：为空则省略，交由网关按默认 Agent 路由（不写空串）。
        if (agentId != null && !agentId.isBlank()) {
            metadata.put("agentId", agentId);
        }
        // 业务附加属性（trace / correlation 等，FEAT-006「业务上下文与凭证传递」MUST）。
        // 收在单独的嵌套对象里，避免与 agentId / clientTools 等保留键冲突；
        // 为空则整段省略，使不用该能力的调用方报文与既有链路逐字节一致。
        if (attributes != null && !attributes.isEmpty()) {
            ObjectNode attrs = metadata.putObject("attributes");
            for (Map.Entry<String, String> e : attributes.entrySet()) {
                attrs.put(e.getKey(), e.getValue());
            }
        }
        // 006 §3.5 ① / 007 §3.1：未声明 exposure → ToolView 为空 → 不上报 clientTools（整段省略，不写空数组）。
        if (clientTools != null && !clientTools.isEmpty()) {
            ArrayNode tools = metadata.putArray("clientTools");
            for (ToolWireSpec spec : clientTools) {
                ObjectNode t = tools.addObject();
                t.put("name", spec.name());
                t.put("description", spec.description());
                try {
                    t.set("inputSchema", mapper.readTree(spec.inputSchema()));
                } catch (JsonProcessingException e) {
                    t.put("inputSchema", spec.inputSchema());
                }
            }
        }
    }

    /**
     * 状态查询请求。
     *
     * <p>方法名与参数位置须与网关契约严格一致：PascalCase 的 {@code GetTask} + {@code params.id}
     * （标准 A2A {@code TaskQueryParams.id}）。agent-runtime-java 的
     * {@code A2aJsonRpcParamsParser.parseTaskQueryParams} 显式校验 {@code params.id} 非空，
     * 缺失会返回 {@code INVALID_PARAMS}。
     *
     * @param taskRef 任务引用
     * @return get 请求
     */
    ObjectNode buildGet(String taskRef) {
        ObjectNode root = newRequest("GetTask");
        root.putObject("params").put("id", taskRef);
        return root;
    }

    /** SubscribeToTask 请求。断点 cursor 通过标准 Last-Event-ID header 承载。 */
    ObjectNode buildSubscribe(String taskRef) {
        ObjectNode root = newRequest("SubscribeToTask");
        root.putObject("params").put("id", taskRef);
        return root;
    }

    /**
     * JSON 文本。
     *
     * @param node ObjectNode
     * @return JSON 文本
     */

    String write(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new A2aTransportException("failed to serialize JSON-RPC request", e);
        }
    }

    /**
     * 解析后的 JsonNode。
     *
     * @param body String
     * @return 解析后的 JsonNode
     */

    JsonNode readTree(String body) {
        try {
            return mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new A2aTransportException("failed to parse JSON-RPC response: " + body, e);
        }
    }

    // ---------- 响应解析 ----------

    /**
     * 中立解析结果。{@code state} 为空表示该帧仅承载内容增量（artifact-update）。
     *
     * @param taskId 任务标识
     * @param contextId 上下文标识
     * @param state 任务状态
     * @param interrupt 中断信息
     * @param text 文本内容
     * @param errorCode 错误码
     * @param errorMessage 错误信息
     */
    record Frame(String taskId, String contextId, TaskState state, Interrupt interrupt,
            String text, String errorCode, String errorMessage, ProtocolArtifact artifact,
            List<ProtocolArtifact> taskArtifacts) {
        // 仅规范构造器，无额外成员。
    }

    record Interrupt(boolean userInput, String toolCallId, String toolName,
            Map<String, Object> arguments, String prompt, Long deadlineMs) {
        // 仅规范构造器，无额外成员。
    }

    /**
     * 帧 Optional。
     *
     * @param result JsonNode
     * @return 帧 Optional
     */

    Optional<Frame> parseFrame(JsonNode result) {
        if (result == null || result.isNull()) {
            return Optional.empty();
        }
        // agent-runtime-java 的 result 用成员名区分事件类型（对齐 documents/zh/2.开发指南/对话接口输入与输出.md）：
        //   流式：result.statusUpdate / result.artifactUpdate
        //   非流式：result.task（完整 Task 对象，status 位于 task.status）
        //   即时消息：result.message
        // 兼容回退：旧 mock 形态用 result.kind 字段（status-update / artifact-update）。
        String legacyKind = result.path("kind").asText("");

        if (result.has("artifactUpdate") || "artifact-update".equals(legacyKind)) {
            JsonNode update = result.has("artifactUpdate") ? result.path("artifactUpdate") : result;
            JsonNode art = result.has("artifactUpdate")
                    ? update.path("artifact")
                    : result.path("artifact");
            ProtocolArtifact protocolArtifact = parseArtifact(art,
                    update.path("append").asBoolean(false), update.path("lastChunk").asBoolean(false));
            String text = !protocolArtifact.agentEventDeclared() && !protocolArtifact.controllerOutput()
                    ? collectArtifactText(art).orElse(null) : null;
            String taskId = result.has("artifactUpdate")
                    ? result.path("artifactUpdate").path("taskId").asText(null)
                    : firstText(result, "id", "taskId").orElse(null);
            String contextId = result.has("artifactUpdate")
                    ? result.path("artifactUpdate").path("contextId").asText(null)
                    : result.path("contextId").asText(null);
            return Optional.of(new Frame(taskId, contextId, null, null, text, null, null,
                    protocolArtifact, List.of()));
        }

        // status 节点定位：流式在 result.statusUpdate.status；非流式在 result.task.status；
        // 旧 mock 兼容在 result.status。
        JsonNode statusUpdate = result.path("statusUpdate");
        JsonNode taskNode = result.path("task");
        boolean isStatusUpdate = !statusUpdate.isMissingNode() && !statusUpdate.isNull();
        boolean isTask = !taskNode.isMissingNode() && !taskNode.isNull();

        JsonNode status;
        String taskId;
        String contextId;
        if (isStatusUpdate) {
            status = statusUpdate.path("status");
            taskId = statusUpdate.path("taskId").asText(null);
            contextId = statusUpdate.path("contextId").asText(null);
        } else if (isTask) {
            status = taskNode.path("status");
            taskId = taskNode.path("id").asText(null);
            contextId = taskNode.path("contextId").asText(null);
        } else {
            // 旧 mock 兼容：result 直接含 status / id / taskId / contextId。
            status = result.path("status");
            taskId = firstText(result, "id", "taskId").orElse(null);
            contextId = result.path("contextId").asText(null);
        }

        String stateStr = status.path("state").asText(null);
        TaskState state = mapState(stateStr).orElse(null);
        String text = collectMessageText(status.path("message")).orElse(null);
        // 完整 Task 的最终业务结果位于 artifacts，而不是 status.message。后者主要承载
        // INPUT_REQUIRED 提示或状态说明。BUS unary 响应和流式 TERMINAL 投影都会返回完整 Task；
        // COMPLETED 时优先读取 artifacts，才能与 runtime HTTP 入口的 Task 语义保持一致。
        if (isTask && state == TaskState.COMPLETED) {
            text = collectTaskArtifactText(taskNode).orElse(text);
        }
        Interrupt interrupt = parseInterrupt(result, status).orElse(null);
        String errorCode = result.path("metadata").path("errorCode").asText(null);
        if (errorCode == null && isTask) {
            // 非流式 task 形态的错误码可能在 task.metadata。
            errorCode = taskNode.path("metadata").path("errorCode").asText(null);
        }
        List<ProtocolArtifact> taskArtifacts = isTask ? parseTaskArtifacts(taskNode) : List.of();
        return Optional.of(new Frame(taskId, contextId, state, interrupt, text, errorCode, text,
                null, taskArtifacts));
    }

    private List<ProtocolArtifact> parseTaskArtifacts(JsonNode task) {
        JsonNode artifacts = task.path("artifacts");
        if (!artifacts.isArray()) {
            return List.of();
        }
        List<ProtocolArtifact> result = new java.util.ArrayList<>();
        for (JsonNode artifact : artifacts) {
            result.add(parseArtifact(artifact, false, true));
        }
        return List.copyOf(result);
    }

    private ProtocolArtifact parseArtifact(JsonNode artifact, boolean append, boolean lastChunk) {
        String artifactId = artifact.path("artifactId").asText(null);
        if (artifactId == null || artifactId.isBlank()) {
            artifactId = "anonymous-" + Integer.toHexString(artifact.toString().hashCode());
        }
        List<ProtocolPart> parts = new java.util.ArrayList<>();
        boolean controllerOutput = false;
        JsonNode partNodes = artifact.path("parts");
        if (partNodes.isArray()) {
            for (JsonNode part : partNodes) {
                if (part.has("text")) {
                    parts.add(new ProtocolPart.Text(part.path("text").asText("")));
                } else if (part.has("data")) {
                    Object data = mapper.convertValue(part.path("data"), Object.class);
                    parts.add(new ProtocolPart.Data(data));
                    controllerOutput |= "controller_output".equals(part.path("data").path("type").asText());
                }
            }
        }
        JsonNode agentEventNode = artifact.path("metadata").path("agentEvent");
        boolean agentEventDeclared = !agentEventNode.isMissingNode() && !agentEventNode.isNull();
        return new ProtocolArtifact(artifactId, parts, append, lastChunk,
                parseAgentEvent(agentEventNode), agentEventDeclared, controllerOutput);
    }

    private static AgentEvent parseAgentEvent(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        AgentEvent event = new AgentEvent(node.path("type").asText(null),
                parseAgentRef(node.path("source")), parseAgentRef(node.path("target")),
                node.path("state").asText(null));
        return event.valid() ? event : null;
    }

    private static AgentEvent.AgentRef parseAgentRef(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new AgentEvent.AgentRef(node.path("agentId").asText(null), node.path("taskId").asText(null));
    }

    /**
     * parseInterrupt。
     *
     * @param result JsonNode
     * @param status JsonNode
     * @return parseInterrupt
     */

    private Optional<Interrupt> parseInterrupt(JsonNode result, JsonNode status) {
        // 权威路径：status.message.metadata._interrupt（对齐 Feat-Func-009 §6.3 / 006 §3.5 ② / 007 §3.5 ②）。
        // 兼容回退：部分旧形态可能置于 status.metadata 或 result.metadata。
        JsonNode node = status.path("message").path("metadata").path("_interrupt");
        if (node.isMissingNode() || node.isNull()) {
            node = status.path("metadata").path("_interrupt");
        }
        if (node.isMissingNode() || node.isNull()) {
            node = result.path("metadata").path("_interrupt");
        }
        if (node.isMissingNode() || node.isNull()) {
            return Optional.empty();
        }
        // _interrupt_kind 与 arguments 嵌套在 context 下（权威）；兼容回退到顶层扁平形态。
        JsonNode context = node.path("context");
        JsonNode kindNode = context.has("_interrupt_kind") ? context.path("_interrupt_kind") : node.path("kind");
        String ikind = kindNode.asText("client_tool");
        JsonNode argsNode = context.has("arguments") ? context.path("arguments") : node.path("arguments");
        String toolCallId = node.path("toolCallId").asText(null);
        String toolName = node.path("toolName").asText(null);
        String prompt = node.path("message").asText(null);
        if (prompt == null) {
            prompt = node.path("prompt").asText(null);
        }
        Long deadlineMs = node.has("deadlineMs") ? node.get("deadlineMs").asLong() : null;
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (argsNode.isObject()) {
            arguments = mapper.convertValue(argsNode, Map.class);
        }
        boolean userInput = "user_input".equals(ikind);
        return Optional.of(new Interrupt(userInput, toolCallId, toolName, arguments, prompt, deadlineMs));
    }

    /**
     * collectMessageText。
     *
     * @param message JsonNode
     * @return collectMessageText
     */

    private Optional<String> collectMessageText(JsonNode message) {
        if (message == null || message.isMissingNode() || message.isNull()) {
            return Optional.empty();
        }
        return collectPartsText(message.path("parts"));
    }

    /**
     * collectArtifactText。
     *
     * @param artifact JsonNode
     * @return collectArtifactText
     */

    private Optional<String> collectArtifactText(JsonNode artifact) {
        if (artifact == null || artifact.isMissingNode()) {
            return Optional.empty();
        }
        return collectPartsText(artifact.path("parts"));
    }

    /**
     * Collects the final output carried by a complete Task's artifacts.
     *
     * @param task complete A2A Task node
     * @return concatenated artifact text, or empty when the Task has no textual artifact
     */
    private Optional<String> collectTaskArtifactText(JsonNode task) {
        JsonNode artifacts = task.path("artifacts");
        if (!artifacts.isArray()) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode artifact : artifacts) {
            if (artifact.path("metadata").has("agentEvent") || isControllerOutput(artifact)) {
                continue;
            }
            collectArtifactText(artifact).ifPresent(text::append);
        }
        return text.length() > 0 ? Optional.of(text.toString()) : Optional.empty();
    }

    private static boolean isControllerOutput(JsonNode artifact) {
        JsonNode parts = artifact.path("parts");
        if (!parts.isArray()) {
            return false;
        }
        for (JsonNode part : parts) {
            if ("controller_output".equals(part.path("data").path("type").asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * collectPartsText。
     *
     * @param parts JsonNode
     * @return collectPartsText
     */

    private Optional<String> collectPartsText(JsonNode parts) {
        if (parts == null || !parts.isArray()) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder();
        for (JsonNode p : parts) {
            // 标准 A2A Part 按字段名联合区分：TextPart = {text}，DataPart = {data}，FilePart = {file}。
            // 兼容旧 mock 形态：{kind:"text", text:"..."}。
            if (p.has("text")) {
                sb.append(p.path("text").asText(""));
            } else if (p.has("data")) {
                // Runtime ChunkMapper maps a structured QueryResponse result to DataPart. The confirmed
                // text contract is data.content; arbitrary data.text/data.message fields remain structured
                // business data and must not be guessed as InvocationSnapshot.outputText.
                JsonNode data = p.path("data");
                String text = data.path("content").asText("");
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            } else {
                // 其他 Part 类型（如 FilePart）当前不参与文本提取，跳过。
                continue;
            }
        }
        return sb.length() > 0 ? Optional.of(sb.toString()) : Optional.empty();
    }

    /**
     * firstText。
     *
     * @param node JsonNode
     * @param fields String...
     * @return firstText
     */

    private static Optional<String> firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isEmpty()) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /**
     * mapState。
     *
     * @param s String
     * @return mapState
     */

    private static Optional<TaskState> mapState(String s) {
        if (s == null) {
            return Optional.empty();
        }
        // 权威值为 TASK_STATE_* 大写带前缀（Feat-Func-009 §6.3 / 006 §3.3）；兼容小写过渡期形态。
        return Optional.of(switch (s) {
            case "TASK_STATE_SUBMITTED", "submitted" -> TaskState.SUBMITTED;
            case "TASK_STATE_WORKING", "working" -> TaskState.WORKING;
            case "TASK_STATE_INPUT_REQUIRED", "input-required" -> TaskState.INPUT_REQUIRED;
            case "TASK_STATE_COMPLETED", "completed" -> TaskState.COMPLETED;
            case "TASK_STATE_FAILED", "failed" -> TaskState.FAILED;
            case "TASK_STATE_CANCELED", "TASK_STATE_CANCELLED",
                    "canceled", "cancelled" -> TaskState.CANCELED;
            case "TASK_STATE_REJECTED", "rejected" -> TaskState.REJECTED;
            default -> TaskState.UNKNOWN;
        });
    }
}
