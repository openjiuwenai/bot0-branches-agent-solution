/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.customer.agent.customrest.abcde;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ABCDE Agent gateway protocol adapter for the A2A bridge Custom REST SPI.
 *
 * <p>Maps ABCDE-specific REST API fields (input/query/message aliases,
 * conversion_id fallback, x-tenant-id header) to standard A2A protocol,
 * and unwraps A2A SDK StreamingEventKind objects into flat
 * {@code {event, content, data}} format inside ABCDE 7-field response envelopes.</p>
 *
 * <p>Unwrapping is necessary because the frontend's {@code normalizeRestFrame}
 * expects flat {@code custom_rsp_data.event/content/data} fields, while A2A SDK objects
 * nest custom event data inside {@code artifact.parts[].text} (double-JSON-encoded).
 * Without unwrapping, the frontend cannot extract event names or content,
 * causing all SSE frames to be silently discarded.</p>
 *
 * @since 2026-07-24
 */
public final class EdpaAbcdeCustomRestAdapter implements CustomRestProtocolAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaAbcdeCustomRestAdapter.class);

    private static final Map<TaskState, String> STATUS_EVENT_MAP = Map.of(
            TaskState.TASK_STATE_SUBMITTED, "task_submitted",
            TaskState.TASK_STATE_WORKING, "task_working",
            TaskState.TASK_STATE_COMPLETED, "task_completed",
            TaskState.TASK_STATE_INPUT_REQUIRED, "task_input_required",
            TaskState.TASK_STATE_FAILED, "task_failed",
            TaskState.TASK_STATE_CANCELED, "task_canceled",
            TaskState.TASK_STATE_REJECTED, "task_rejected"
    );

    private final ObjectMapper objectMapper;

    public EdpaAbcdeCustomRestAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        LOGGER.info("[EDP-CUSTOM-REST] EdpaAbcdeCustomRestAdapter initialized");
    }

    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        String conversationId = resolveConversationId(context);
        String inputText = resolveMessageText(context);

        LOGGER.info("[EDP-CUSTOM-REST-REQ] Received request: conversationId={}, streaming={}, tenant={}",
                abbreviate(conversationId, 8),
                resolveStream(context),
                firstHeader(context.headers(), "x-tenant-id").orElse(null));

        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart(inputText))
                .messageId(UUID.randomUUID().toString())
                .contextId(conversationId)
                .build();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("body", context.body());
        metadata.put("headers", flatten(context.headers()));
        metadata.put("query", flatten(context.queryParams()));
        metadata.put("path_variables", context.pathVariables());

        String tenantId = firstHeader(context.headers(), "x-tenant-id").orElse(null);

        MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .metadata(metadata)
                .tenant(tenantId)
                .build();

        boolean streaming = resolveStream(context);

        LOGGER.info("[EDP-CUSTOM-REST] Mapped request to A2A: conversationId={}, messageId={}, streaming={}",
                abbreviate(conversationId, 8), message.messageId(), streaming);

        return new A2ASendCommand(params, streaming);
    }

    @Override
    public Object fromA2ATask(Task task, Context context) {
        LOGGER.debug("[EDP-CUSTOM-REST-RSP] Building sync response: taskId={}, state={}, conversationId={}",
                task.id(),
                task.status() != null ? task.status().state() : "null",
                abbreviate(task.contextId(), 8));

        Map<String, Object> flatData = unwrapTaskToFlat(task);
        Map<String, Object> result = envelope(true, "", flatData, context);
        LOGGER.debug("[EDP-CUSTOM-REST-RSP] Sync response envelope: dataKeys={}",
                flatData != null ? flatData.keySet() : "null");
        return result;
    }

    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        String sseType = detectEventType(event);
        String eventType = event.getClass().getSimpleName();
        Map<String, Object> flatData = unwrapToFlat(event);

        // 合并 3 行 DEBUG 为 1 行：事件类型 + flatData 的 event 字段 + dataKeys
        LOGGER.debug("[EDP-CUSTOM-REST-RSP] Stream event: type={}, flatEvent={}, dataKeys={}",
                eventType,
                flatData != null ? flatData.getOrDefault("event", "?") : "null",
                flatData != null ? flatData.keySet() : "null");

        return new SseEvent(sseType, envelope(true, "", flatData, context));
    }

    @Override
    public Object fromError(CustomRestError error, Context context) {
        LOGGER.error("[EDP-CUSTOM-REST-ERR] Non-stream error: httpStatus={}, code={}, message={}, conversationId={}",
                error.httpStatus(), error.code(), error.message(),
                abbreviate(context.pathVariables().getOrDefault("conversation_id", ""), 8));

        Map<String, Object> flatData = new LinkedHashMap<>();
        flatData.put("event", "error");
        flatData.put("content", error.message());
        flatData.put("data", Map.of("httpStatus", error.httpStatus(), "code", error.code()));
        return envelope(false, error.message(), flatData, context);
    }

    @Override
    public SseEvent fromStreamError(CustomRestError error, Context context) {
        LOGGER.error("[EDP-CUSTOM-REST-ERR] Stream error: httpStatus={}, code={}, message={}, conversationId={}",
                error.httpStatus(), error.code(), error.message(),
                abbreviate(context.pathVariables().getOrDefault("conversation_id", ""), 8));

        Map<String, Object> flatData = new LinkedHashMap<>();
        flatData.put("event", "error");
        flatData.put("content", error.message());
        flatData.put("data", Map.of("httpStatus", error.httpStatus(), "code", error.code()));
        return new SseEvent("error", envelope(false, error.message(), flatData, context));
    }

    /**
     * Unwrap any StreamingEventKind to flat format for frontend consumption.
     *
     * @param event A2A SDK StreamingEventKind 事件对象
     * @return 扁平化后的 {event, content, data} 格式 Map
     */
    private Map<String, Object> unwrapToFlat(StreamingEventKind event) {
        if (event instanceof TaskArtifactUpdateEvent artifact) {
            return unwrapArtifactToFlat(artifact);
        }
        if (event instanceof TaskStatusUpdateEvent status) {
            return mapStatusToFlat(status);
        }
        if (event instanceof Task task) {
            return unwrapTaskToFlat(task);
        }
        if (event instanceof Message msg) {
            return unwrapMessageToFlat(msg);
        }
        // Unknown type — best-effort flat format
        LOGGER.warn("[EDP-CUSTOM-REST-RSP] Unknown StreamingEventKind type: {}, falling back to 'unknown' event",
                event.getClass().getName());
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("event", "unknown");
        flat.put("content", "");
        return flat;
    }

    /**
     * Unwrap TaskArtifactUpdateEvent: extract custom event payload from
     * artifact.parts[].text (double-JSON-encoded), lift to flat format.
     *
     * @param artifactEvent A2A SDK TaskArtifactUpdateEvent 事件对象
     * @return 扁平化后的 {event, content, data} 格式 Map
     */
    private Map<String, Object> unwrapArtifactToFlat(TaskArtifactUpdateEvent artifactEvent) {
        Artifact artifact = artifactEvent.artifact();
        if (artifact == null || artifact.parts() == null || artifact.parts().isEmpty()) {
            return Map.of("event", "artifact_empty", "content", "");
        }

        String text = concatArtifactText(artifact);

        // TextPart 提取为空时，尝试从 DataPart 提取结构化事件数据
        if (text.isEmpty()) {
            String dataPartJson = extractDataPartJson(artifact);
            if (!dataPartJson.isEmpty()) {
                Map<String, Object> parsed = parseArtifactJsonEnvelope(dataPartJson);
                if (!parsed.isEmpty()) {
                    return parsed;
                }
                // DataPart JSON 解析失败，回退为 summary
                Map<String, Object> flat = new LinkedHashMap<>();
                flat.put("event", "summary");
                flat.put("content", dataPartJson);
                return flat;
            }
            return Map.of("event", "artifact_empty", "content", "");
        }

        // 尝试 JSON 解析，失败则当作 plain text
        Map<String, Object> parsed = parseArtifactJsonEnvelope(text);
        if (!parsed.isEmpty()) {
            return parsed;
        }

        // Plain text — wrap as summary event
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("event", "summary");
        flat.put("content", text);
        return flat;
    }

    /**
     * 从 Artifact 的 parts 中拼接所有 TextPart 文本内容。
     *
     * @param artifact A2A SDK Artifact 对象
     * @return 拼接后的文本内容
     */
    private String concatArtifactText(Artifact artifact) {
        StringBuilder textBuilder = new StringBuilder();
        for (Part<?> part : artifact.parts()) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                textBuilder.append(textPart.text());
            }
        }
        return textBuilder.toString();
    }

    /**
     * 从 Artifact 的 parts 中提取 DataPart 的 data() 内容，序列化为 JSON 字符串。
     * DataPart 的 data() 返回一个 Map，结构如：
     *   {type:"custom", index:0, payload:{event, content, data, ...}}
     * 序列化后可直接传入 parseArtifactJsonEnvelope 进行解析。
     *
     * @param artifact A2A SDK Artifact 对象
     * @return 第一个 DataPart 的 data 序列化后的 JSON 字符串；无 DataPart 时返回空字符串
     */
    private String extractDataPartJson(Artifact artifact) {
        for (Part<?> part : artifact.parts()) {
            if (part instanceof DataPart dataPart && dataPart.data() != null) {
                try {
                    return objectMapper.writeValueAsString(dataPart.data());
                } catch (JsonProcessingException e) {
                    LOGGER.warn("[EDP-CUSTOM-REST] Failed to serialize DataPart data. error={}",
                            e.getMessage());
                }
            }
        }
        return "";
    }

    /**
     * 解析 artifact text 为 JSON 信封格式，返回扁平 Map。
     * 支持3种 JSON 结构：custom payload、flat event、LLM output。
     * 解析失败时返回 null（由调用方决定回退策略）。
     *
     * @param text JSON 文本内容
     * @return 扁平化 Map，解析失败时返回 null
     */
    private Map<String, Object> parseArtifactJsonEnvelope(String text) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(text,
                    new TypeReference<Map<String, Object>>() {});

            Object type = parsed.get("type");
            Object payload = parsed.get("payload");

            // Shape 1: {type:"custom"|"llm_usage"|"llm_output", payload:{event, content, data}}
            if (type instanceof String && payload instanceof Map) {
                Map<String, Object> payloadMap = (Map<String, Object>) payload;
                Map<String, Object> flat = new LinkedHashMap<>();
                // 优先使用 payload 中的 event 字段，回退到 type 字段
                Object eventVal = payloadMap.get("event");
                if (eventVal == null) {
                    eventVal = type;
                }
                flat.put("event", String.valueOf(eventVal));
                flat.put("content", String.valueOf(payloadMap.getOrDefault("content", "")));
                if (payloadMap.get("data") != null) {
                    flat.put("data", payloadMap.get("data"));
                }
                copyIfPresent(flat, payloadMap, "tool", "interrupt_id", "timestamp",
                        "conversation_id", "index");
                return flat;
            }

            // Shape 2: already flat {event, content, data}
            if (parsed.containsKey("event")) {
                return new LinkedHashMap<>(parsed);
            }

            // Shape 3: LLM output {type:"llm_output", ...}
            if (type instanceof String typeName && !typeName.isEmpty()) {
                Map<String, Object> flat = new LinkedHashMap<>();
                flat.put("event", typeName);
                flat.put("content", text);
                return flat;
            }
        } catch (JsonProcessingException ex) {
            LOGGER.warn("[EDP-CUSTOM-REST] Failed to parse artifact text as JSON, "
                    + "treating as plain text. textLen={}, error={}",
                    text.length(), ex.getMessage());
        }
        return Collections.emptyMap();
    }

    /**
     * Map TaskStatusUpdateEvent state to frontend-recognizable event name.
     *
     * @param statusEvent A2A SDK TaskStatusUpdateEvent 状态更新事件
     * @return 包含 event/content/data 的扁平化 Map
     */
    private Map<String, Object> mapStatusToFlat(TaskStatusUpdateEvent statusEvent) {
        String eventName = STATUS_EVENT_MAP.getOrDefault(
                statusEvent.status().state(), "task_status");

        LOGGER.debug("[EDP-CUSTOM-REST-RSP] Unwrapped status event: state={}, taskId={}",
                statusEvent.status().state(), statusEvent.taskId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", statusEvent.status().state().name());
        data.put("taskId", statusEvent.taskId());
        if (statusEvent.status().message() != null) {
            StringBuilder sb = new StringBuilder();
            for (Part<?> part : statusEvent.status().message().parts()) {
                if (part instanceof TextPart textPart && textPart.text() != null) {
                    sb.append(textPart.text());
                }
            }
            if (!sb.isEmpty()) {
                data.put("messageContent", sb.toString());
            }
        }

        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("event", eventName);
        flat.put("content", "");
        flat.put("data", data);
        return flat;
    }

    /**
     * Unwrap Task (final result) to flat format.
     *
     * @param task A2A SDK Task 最终结果对象
     * @return 包含 event/content/data 的扁平化 Map
     */
    private Map<String, Object> unwrapTaskToFlat(Task task) {
        String eventName;
        if (task.status() != null && task.status().state() != null) {
            eventName = STATUS_EVENT_MAP.getOrDefault(task.status().state(), "task_result");
        } else {
            eventName = "task_result";
        }

        // Extract content from last agent message in history
        String content = extractLastAgentText(task);

        LOGGER.debug("[EDP-CUSTOM-REST-RSP] Unwrapped task result: state={}, taskId={}, conversationId={}",
                task.status() != null ? task.status().state() : "null",
                task.id(), abbreviate(task.contextId(), 8));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.id());
        data.put("contextId", task.contextId());
        if (task.status() != null) {
            data.put("state", task.status().state().name());
        }

        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("event", eventName);
        flat.put("content", content);
        flat.put("data", data);
        return flat;
    }

    /**
     * Unwrap Message (intermediate) to flat format.
     *
     * @param msg A2A SDK Message 中间消息对象
     * @return 包含 event/content 的扁平化 Map
     */
    private Map<String, Object> unwrapMessageToFlat(Message msg) {
        StringBuilder sb = new StringBuilder();
        if (msg.parts() != null) {
            for (Part<?> part : msg.parts()) {
                if (part instanceof TextPart textPart && textPart.text() != null) {
                    sb.append(textPart.text());
                }
            }
        }

        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("event", "summary");
        flat.put("content", sb.toString());
        return flat;
    }

    /**
     * Extract text from the last ROLE_AGENT message in Task history.
     *
     * @param task A2A SDK Task 对象，包含对话历史
     * @return 最后一条 AGENT 消息的文本内容；无匹配时返回空字符串
     */
    private static String extractLastAgentText(Task task) {
        if (task.history() == null || task.history().isEmpty()) {
            return "";
        }
        // Iterate backwards to find last agent message
        for (int i = task.history().size() - 1; i >= 0; i--) {
            Message msg = task.history().get(i);
            if (msg.role() == Message.Role.ROLE_AGENT && msg.parts() != null) {
                String text = extractTextFromMessage(msg);
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        return "";
    }

    /**
     * 从单条 Message 中提取所有 TextPart 的文本内容。
     *
     * @param msg A2A SDK Message 对象
     * @return 拼接后的文本内容；无 TextPart 时返回空字符串
     */
    private static String extractTextFromMessage(Message msg) {
        StringBuilder sb = new StringBuilder();
        for (Part<?> part : msg.parts()) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                sb.append(textPart.text());
            }
        }
        return sb.toString();
    }

    /**
     * Detect SSE event type name based on StreamingEventKind content.
     *
     * @param event A2A SDK StreamingEventKind 事件对象
     * @return SSE 事件类型名称（"final"、"interrupt" 或 "chunk"）
     */
    private static String detectEventType(StreamingEventKind event) {
        if (event instanceof TaskStatusUpdateEvent status && status.isFinalOrInterrupted()) {
            return status.status().state().isInterrupted() ? "interrupt" : "final";
        }
        if (event instanceof Task task && task.status() != null && task.status().state() != null
                && (task.status().state().isFinal() || task.status().state().isInterrupted())) {
            return task.status().state().isInterrupted() ? "interrupt" : "final";
        }
        return "chunk";
    }

    /**
     * ABCDE 7-field response envelope with flat {@code custom_rsp_data}.
     *
     * @param success 是否成功
     * @param error 错误消息（成功时为空字符串）
     * @param flatData 扁平化的自定义响应数据
     * @param context 请求上下文
     * @return 包含 7 个字段的响应 Map
     */
    private static Map<String, Object> envelope(boolean success, String error,
            Object flatData, Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("agent_id", stringValue(context.body().get("agent_id"),
                context.pathVariables().get("agent_id")));
        result.put("conversation_id", context.pathVariables().getOrDefault("conversation_id", ""));
        result.put("output", "");
        result.put("error", error);
        result.put("execution_time", "");
        result.put("custom_rsp_data", flatData);
        return result;
    }

    private static String resolveConversationId(Context context) {
        String pathConvId = context.pathVariables().get("conversation_id");
        if (pathConvId != null && !pathConvId.isBlank()) {
            LOGGER.debug("[EDP-CUSTOM-REST-REQ] Resolved conversationId from path variable: {}",
                    abbreviate(pathConvId, 8));
            return pathConvId;
        }
        Object bodyConvId = context.body().get("conversation_id");
        if (bodyConvId instanceof String s && !s.isBlank()) {
            LOGGER.debug("[EDP-CUSTOM-REST-REQ] Resolved conversationId from body: {}",
                    abbreviate(s, 8));
            return s;
        }
        Object bodyAlias = context.body().get("conversion_id");
        if (bodyAlias instanceof String s && !s.isBlank()) {
            LOGGER.debug("[EDP-CUSTOM-REST-REQ] Resolved conversationId from conversion_id alias: {}",
                    abbreviate(s, 8));
            return s;
        }
        LOGGER.warn("[EDP-CUSTOM-REST-REQ] No conversationId resolved from path or body");
        return pathConvId;
    }

    private String resolveMessageText(Context context) {
        Object input = context.body().get("input");
        Object query = context.body().get("query");
        Object msg = context.body().get("message");
        Object source = input != null ? input : query != null ? query : msg;
        if (source == null) {
            LOGGER.warn("[EDP-CUSTOM-REST-REQ] No input/query/message found in request body, keys present={}",
                    context.body().keySet());
            return "";
        }
        if (source instanceof String text) {
            return text;
        }
        try {
            return objectMapper.writeValueAsString(source);
        } catch (JsonProcessingException ex) {
            LOGGER.error("[EDP-CUSTOM-REST-ERR] Message serialization failed for source type: {}",
                    source.getClass().getName(), ex);
            throw new IllegalArgumentException("message serialization failed", ex);
        }
    }

    private static boolean resolveStream(Context context) {
        Object stream = context.body().get("stream");
        if (stream == null) {
            return true;
        }
        if (stream instanceof Boolean b) {
            return b;
        }
        if (stream instanceof String s && !s.isBlank()) {
            return Boolean.parseBoolean(s);
        }
        return true;
    }

    private static Optional<String> firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return (values != null && !values.isEmpty()) ? Optional.of(values.get(0)) : Optional.empty();
    }

    private static Map<String, Object> flatten(Map<String, List<String>> source) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        source.forEach((key, values) -> {
            if (values == null || values.isEmpty()) {
                flattened.put(key, "");
            } else if (values.size() == 1) {
                flattened.put(key, values.get(0));
            } else {
                flattened.put(key, values);
            }
        });
        return flattened;
    }

    private static void copyIfPresent(Map<String, Object> target, Map<String, Object> source,
            String... keys) {
        for (String key : keys) {
            if (source.containsKey(key) && source.get(key) != null) {
                target.put(key, source.get(key));
            }
        }
    }

    private static String stringValue(Object preferred, String fallback) {
        return preferred != null && !String.valueOf(preferred).isBlank() ? String.valueOf(preferred)
                : fallback != null ? fallback : "";
    }

    /**
     * Truncate string to maxLen characters, appending "..." if truncated.
     * Used for logging to avoid excessive output.
     *
     * @param s 需要截断的字符串
     * @param maxLen 最大保留字符数
     * @return 截断后的字符串；若原字符串为 null 则返回 "null"
     */
    private static String abbreviate(String s, int maxLen) {
        if (s == null) {
            return "null";
        }
        if (s.length() <= maxLen) {
            return s;
        }
        return s.substring(0, maxLen) + "...";
    }
}
