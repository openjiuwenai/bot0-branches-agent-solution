/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.customer.agent.customrest.abcde;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    // ===== Request mapping =====

    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        String conversationId = resolveConversationId(context);
        String inputText = resolveMessageText(context);

        LOGGER.info("[EDP-CUSTOM-REST-REQ] Received request: conversationId={}, streaming={}, tenant={}",
                abbreviate(conversationId, 8),
                resolveStream(context),
                firstHeader(context.headers(), "x-tenant-id"));

        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .parts(new TextPart(inputText))
                .messageId(UUID.randomUUID().toString())
                .build();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("body", context.body());
        metadata.put("headers", flatten(context.headers()));
        metadata.put("query", flatten(context.queryParams()));
        metadata.put("path_variables", context.pathVariables());

        String tenantId = firstHeader(context.headers(), "x-tenant-id");

        MessageSendParams params = MessageSendParams.builder()
                .message(message)
                .metadata(metadata)
                .tenant(tenantId)
                .build();

        boolean streaming = resolveStream(context);

        LOGGER.info("[EDP-CUSTOM-REST] Mapped request to A2A: conversationId={}, messageId={}, streaming={}",
                abbreviate(conversationId, 8), message.messageId(), streaming);

        return new A2ASendCommand(params, conversationId, streaming);
    }

    // ===== Synchronous response =====

    @Override
    public Object fromA2ATask(Task task, Context context) {
        String externalId = context.pathVariables().getOrDefault("conversation_id", "");

        LOGGER.debug("[EDP-CUSTOM-REST-RSP] Building sync response: taskId={}, state={}, conversationId={}",
                task.id(),
                task.status() != null ? task.status().state() : "null",
                abbreviate(externalId, 8));

        Map<String, Object> flatData = unwrapTaskToFlat(task, externalId);
        return envelope(true, "", flatData, context);
    }

    // ===== Stream event =====

    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        String externalId = context.pathVariables().getOrDefault("conversation_id", "");
        String sseType = detectEventType(event);

        LOGGER.debug("[EDP-CUSTOM-REST-RSP] Processing stream event: eventType={}, conversationId={}",
                event.getClass().getSimpleName(),
                abbreviate(externalId, 8));

        Map<String, Object> flatData = unwrapToFlat(event, externalId);
        return new SseEvent(sseType, envelope(true, "", flatData, context));
    }

    // ===== Non-stream error =====

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

    // ===== Stream error =====

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

    // ===== Unwrapping: A2A SDK objects → flat {event, content, data} =====

    /** Unwrap any StreamingEventKind to flat format for frontend consumption. */
    private Map<String, Object> unwrapToFlat(StreamingEventKind event, String externalId) {
        if (event instanceof TaskArtifactUpdateEvent artifact) {
            return unwrapArtifactToFlat(artifact, externalId);
        }
        if (event instanceof TaskStatusUpdateEvent status) {
            return mapStatusToFlat(status);
        }
        if (event instanceof Task task) {
            return unwrapTaskToFlat(task, externalId);
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
     */
    private Map<String, Object> unwrapArtifactToFlat(TaskArtifactUpdateEvent artifactEvent,
            String externalId) {
        Artifact artifact = artifactEvent.artifact();
        if (artifact == null || artifact.parts() == null || artifact.parts().isEmpty()) {
            LOGGER.debug("[EDP-CUSTOM-REST-RSP] Received empty artifact in TaskArtifactUpdateEvent");
            return Map.of("event", "artifact_empty", "content", "");
        }

        // Concatenate all TextPart text content
        StringBuilder textBuilder = new StringBuilder();
        for (Part<?> part : artifact.parts()) {
            if (part instanceof TextPart textPart && textPart.text() != null) {
                textBuilder.append(textPart.text());
            }
        }
        String text = textBuilder.toString();
        if (text.isEmpty()) {
            LOGGER.debug("[EDP-CUSTOM-REST-RSP] Received empty artifact in TaskArtifactUpdateEvent");
            return Map.of("event", "artifact_empty", "content", "");
        }

        // Try to parse as JSON envelope: {type:"custom", payload:{event, content, data, ...}}
        try {
            Map<String, Object> parsed = objectMapper.readValue(text,
                    new TypeReference<Map<String, Object>>() {});

            Object type = parsed.get("type");
            Object payload = parsed.get("payload");

            // Shape 1: {type:"custom", payload:{event, content, data}} — EdpaEventRail custom events
            if ("custom".equals(type) && payload instanceof Map) {
                Map<String, Object> payloadMap = (Map<String, Object>) payload;
                Map<String, Object> flat = new LinkedHashMap<>();
                flat.put("event", String.valueOf(payloadMap.getOrDefault("event", "unknown")));
                flat.put("content", String.valueOf(payloadMap.getOrDefault("content", "")));
                if (payloadMap.get("data") != null) {
                    flat.put("data", payloadMap.get("data"));
                }
                // Preserve useful payload fields: tool, interrupt_id, etc.
                copyIfPresent(flat, payloadMap, "tool", "interrupt_id", "timestamp",
                        "conversation_id", "index");
                // Replace internal conversation_id with external one
                if (flat.containsKey("conversation_id")) {
                    flat.put("conversation_id", externalId);
                }
                return flat;
            }

            // Shape 2: already flat {event, content, data} — pass through
            if (parsed.containsKey("event")) {
                Map<String, Object> flat = new LinkedHashMap<>(parsed);
                if (flat.containsKey("conversation_id")) {
                    flat.put("conversation_id", externalId);
                }
                return flat;
            }

            // Shape 3: LLM output {type:"llm_output", ...} — lift type as event name
            if (type instanceof String typeName && !typeName.isEmpty()) {
                Map<String, Object> flat = new LinkedHashMap<>();
                flat.put("event", typeName);
                flat.put("content", text);
                return flat;
            }
        } catch (Exception ex) {
            LOGGER.warn("[EDP-CUSTOM-REST-ERR] Failed to parse artifact text as JSON, treating as plain text. textLen={}, error={}",
                    text.length(), ex.getMessage());
            // JSON parse failed — treat as plain text
        }

        // Plain text — wrap as summary event
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("event", "summary");
        flat.put("content", text);
        return flat;
    }

    /** Map TaskStatusUpdateEvent state to frontend-recognizable event name. */
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

    /** Unwrap Task (final result) to flat format. */
    private Map<String, Object> unwrapTaskToFlat(Task task, String externalId) {
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
                task.id(), abbreviate(externalId, 8));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", task.id());
        data.put("contextId", externalId);
        if (task.status() != null) {
            data.put("state", task.status().state().name());
        }

        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("event", eventName);
        flat.put("content", content);
        flat.put("data", data);
        return flat;
    }

    /** Unwrap Message (intermediate) to flat format. */
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

    /** Extract text from the last ROLE_AGENT message in Task history. */
    private static String extractLastAgentText(Task task) {
        if (task.history() == null || task.history().isEmpty()) return "";
        // Iterate backwards to find last agent message
        for (int i = task.history().size() - 1; i >= 0; i--) {
            Message msg = task.history().get(i);
            if (msg.role() == Message.Role.ROLE_AGENT && msg.parts() != null) {
                StringBuilder sb = new StringBuilder();
                for (Part<?> part : msg.parts()) {
                    if (part instanceof TextPart textPart && textPart.text() != null) {
                        sb.append(textPart.text());
                    }
                }
                if (!sb.isEmpty()) return sb.toString();
            }
        }
        return "";
    }

    // ===== SSE event type detection =====

    /** Detect SSE event type name based on StreamingEventKind content. */
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

    // ===== Envelope =====

    /** ABCDE 7-field response envelope with flat custom_rsp_data. */
    private static Map<String, Object> envelope(boolean success, String error,
            Object flatData, Context context) {
        LOGGER.debug("[EDP-CUSTOM-REST-RSP] Building response envelope: success={}, dataKeys={}",
                success, flatData != null ? ((Map<String, Object>) flatData).keySet() : "null");

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

    // ===== Request mapping helpers =====

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
        if (source instanceof String text) return text;
        try {
            return objectMapper.writeValueAsString(source);
        } catch (Exception ex) {
            LOGGER.error("[EDP-CUSTOM-REST-ERR] Message serialization failed for source type: {}",
                    source.getClass().getName(), ex);
            throw new IllegalArgumentException("message serialization failed", ex);
        }
    }

    private static boolean resolveStream(Context context) {
        Object stream = context.body().get("stream");
        if (stream == null) return true;
        if (stream instanceof Boolean b) return b;
        if (stream instanceof String s && !s.isBlank()) return Boolean.parseBoolean(s);
        return true;
    }

    // ===== Utility helpers =====

    private static String firstHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name);
        return values != null && !values.isEmpty() ? values.get(0) : null;
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
     */
    private static String abbreviate(String s, int maxLen) {
        if (s == null) return "null";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }
}
