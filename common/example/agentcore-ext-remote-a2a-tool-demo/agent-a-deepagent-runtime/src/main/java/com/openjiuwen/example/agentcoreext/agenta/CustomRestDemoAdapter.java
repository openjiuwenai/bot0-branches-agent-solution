/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.agenta;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Example customer protocol used to verify the configurable Custom REST entrypoint.
 *
 * @since 0.1.0
 */
public final class CustomRestDemoAdapter implements CustomRestProtocolAdapter {
    private final ObjectMapper objectMapper;

    public CustomRestDemoAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        String conversationId = context.pathVariables().get("conversation_id");
        Object input = context.body().get("input");

        String inputText;
        if (input == null) {
            inputText = "";
        } else if (input instanceof String text) {
            inputText = text;
        } else {
            inputText = objectMapper.valueToTree(input).toString();
        }

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
        MessageSendParams params = MessageSendParams.builder().message(message).metadata(metadata).build();
        Object stream = context.body().get("stream");
        boolean streaming = stream == null
                || (stream instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(stream)));
        return new A2ASendCommand(params, conversationId, streaming);
    }

    @Override
    public Object fromA2ATask(Task task, Context context) {
        return envelope(true, "", externalize(task, context), context);
    }

    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        StreamingEventKind externalEvent = externalize(event, context);
        String type;
        if (externalEvent instanceof TaskStatusUpdateEvent status && status.isFinalOrInterrupted()) {
            type = status.status().state().isInterrupted() ? "interrupt" : "final";
        } else if (externalEvent instanceof Task task && task.status() != null && task.status().state() != null
                && (task.status().state().isFinal() || task.status().state().isInterrupted())) {
            type = task.status().state().isInterrupted() ? "interrupt" : "final";
        } else {
            type = "chunk";
        }
        return new SseEvent(type, envelope(true, "", Map.of("type", type, "data", externalEvent), context));
    }

    @Override
    public Object fromError(CustomRestError error, Context context) {
        return envelope(false, error.message(), Map.of("type", "error", "data", error), context);
    }

    @Override
    public SseEvent fromStreamError(CustomRestError error, Context context) {
        return new SseEvent("error",
                envelope(false, error.message(), Map.of("type", "error", "data", error), context));
    }

    private Map<String, Object> envelope(boolean success, String error, Object raw, Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", success);
        result.put("agent_id", stringValue(context.body().get("agent_id"),
                context.pathVariables().get("agent_id")));
        result.put("conversation_id", context.pathVariables().getOrDefault("conversation_id", ""));
        result.put("output", "");
        result.put("error", error);
        result.put("execution_time", "");
        result.put("custom_rsp_data", raw);
        return result;
    }

    private static StreamingEventKind externalize(StreamingEventKind event, Context context) {
        String externalContextId = context.pathVariables().getOrDefault("conversation_id", "");
        if (event instanceof Task task) {
            return externalize(task, context);
        }
        if (event instanceof Message message) {
            return externalize(message, externalContextId);
        }
        if (event instanceof TaskStatusUpdateEvent status) {
            return TaskStatusUpdateEvent.builder(status)
                    .contextId(externalContextId)
                    .status(externalize(status.status(), externalContextId))
                    .build();
        }
        if (event instanceof TaskArtifactUpdateEvent artifact) {
            return TaskArtifactUpdateEvent.builder(artifact).contextId(externalContextId).build();
        }
        return event;
    }

    private static Task externalize(Task task, Context context) {
        String externalContextId = context.pathVariables().getOrDefault("conversation_id", "");
        var builder = Task.builder(task).contextId(externalContextId);
        if (task.status() != null) {
            builder.status(externalize(task.status(), externalContextId));
        }
        if (task.history() != null) {
            builder.history(task.history().stream()
                    .map(message -> externalize(message, externalContextId))
                    .toList());
        }
        return builder.build();
    }

    private static TaskStatus externalize(TaskStatus status, String externalContextId) {
        Message message = status.message() == null ? null : externalize(status.message(), externalContextId);
        return new TaskStatus(status.state(), message, status.timestamp());
    }

    private static Message externalize(Message message, String externalContextId) {
        return Message.builder(message).contextId(externalContextId).build();
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

    private static String stringValue(Object preferred, String fallback) {
        return preferred != null && !String.valueOf(preferred).isBlank() ? String.valueOf(preferred)
                : fallback != null ? fallback : "";
    }
}
