/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.ascend.client.transport.a2a;

import com.huawei.ascend.client.api.TaskState;
import com.huawei.ascend.client.transport.spi.ToolWireSpec;
import com.huawei.ascend.client.transport.spi.TransportProvider;

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
 * <p>请求侧：把中立指令映射为 {@code SendStreamingMessage}（创建/流式）与 {@code SendMessage}（续跑/同步）。
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
        // 仅 STREAMING 交付：创建用 SendStreamingMessage（SSE）；预留模式回退 SendMessage。
        String method = (cmd.mode() == com.huawei.ascend.client.api.InvocationMode.STREAMING)
                ? "SendStreamingMessage" : "SendMessage";
        ObjectNode root = newRequest(method);
        ObjectNode params = root.putObject("params");
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
        textPart.put("kind", "text");
        textPart.put("text", cmd.input());
        fillMetadata(params, cmd.agentId(), cmd.clientTools());
        return root;
    }

    /**
     * resume 请求。
     *
     * @param cmd TransportProvider.ResumeCommand
     * @return resume 请求
     */

    ObjectNode buildResume(TransportProvider.ResumeCommand cmd) {
        // 工具结果 / 用户输入续跑一律走同步 SendMessage（Feat-Func-011 §5.9.3）：非 SSE、单条 JSON 响应。
        ObjectNode root = newRequest("SendMessage");
        ObjectNode params = root.putObject("params");
        ObjectNode message = params.putObject("message");
        message.put("role", "ROLE_USER");
        // 每次续跑用新的 messageId；taskId 关联既有 Task。toolCallId 不上 wire，由 runtime 按单一 pending 自动关联。
        message.put("messageId", cmd.messageId());
        message.put("taskId", cmd.taskRef());
        // 续跑稳定带 contextId（=原 conversationId，Feat-Func-006 §3.5 ③④ / feat-011 §4.9 AC-5 / §6.9 GW-S4-4）。
        if (cmd.conversationId() != null && !cmd.conversationId().isEmpty()) {
            message.put("contextId", cmd.conversationId());
        }
        ArrayNode parts = message.putArray("parts");
        ObjectNode textPart = parts.addObject();
        textPart.put("kind", "text");
        textPart.put("text", cmd.observationText());
        // 续跑不重复声明 clientTools（仅创建时声明），metadata 保持空对象以对齐 A2A 结构。
        params.putObject("metadata");
        return root;
    }

    private void fillMetadata(ObjectNode params, String agentId, List<ToolWireSpec> clientTools) {
        ObjectNode metadata = params.putObject("metadata");
        // agentId 可选：为空则省略，交由网关按默认 Agent 路由（不写空串）。
        if (agentId != null && !agentId.isBlank()) {
            metadata.put("agentId", agentId);
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
     * get 请求。
     *
     * @param taskRef String
     * @return get 请求
     */

    ObjectNode buildGet(String taskRef) {
        ObjectNode root = newRequest("tasks/get");
        root.putObject("params").put("id", taskRef);
        return root;
    }

    /**
     * cancel 请求。
     *
     * @param taskRef String
     * @param reason String
     * @return cancel 请求
     */

    ObjectNode buildCancel(String taskRef, String reason) {
        ObjectNode root = newRequest("tasks/cancel");
        ObjectNode params = root.putObject("params");
        params.put("id", taskRef);
        if (reason != null) {
            params.put("reason", reason);
        }
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

    /** 中立解析结果。{@code state} 为空表示该帧仅承载内容增量（artifact-update）。 */
    record Frame(String taskId, String contextId, TaskState state, Interrupt interrupt,
            String text, String errorCode, String errorMessage) {
    }
    record Interrupt(boolean userInput, String toolCallId, String toolName,
            Map<String, Object> arguments, String prompt, Long deadlineMs) {
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
        String kind = result.path("kind").asText("");
        String taskId = firstText(result, "id", "taskId").orElse(null);
        String contextId = result.path("contextId").asText(null);

        if ("artifact-update".equals(kind)) {
            String text = collectArtifactText(result.path("artifact")).orElse(null);
            return Optional.of(new Frame(taskId, contextId, null, null, text, null, null));
        }

        JsonNode status = result.path("status");
        String stateStr = status.path("state").asText(null);
        TaskState state = mapState(stateStr).orElse(null);
        String text = collectMessageText(status.path("message")).orElse(null);
        Interrupt interrupt = parseInterrupt(result, status).orElse(null);
        String errorCode = result.path("metadata").path("errorCode").asText(null);
        return Optional.of(new Frame(taskId, contextId, state, interrupt, text, errorCode, text));
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
            if ("text".equals(p.path("kind").asText(""))) {
                sb.append(p.path("text").asText(""));
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
