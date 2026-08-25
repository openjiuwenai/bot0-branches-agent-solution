/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.config;

import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TextPart;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * EDPAgent implementation of {@link CustomRestProtocolAdapter} for custom REST endpoints.
 *
 * <p>Translates between the custom REST protocol and the A2A protocol,
 * mapping incoming requests to A2A messages and projecting A2A responses
 * back to the custom REST format.
 *
 * @since 0.1.0
 */
@Component
public final class EdpaCustomRestAdapter implements CustomRestProtocolAdapter {
    @Override
    public A2ASendCommand toA2ARequest(Context context) {
        String conversationId = context.pathVariables().get("conversation_id");
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = "custom-" + UUID.randomUUID().toString().substring(0, 8);
        }
        Object rawMessage = context.body().get("message");
        String text = rawMessage instanceof String s ? s : "";
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(UUID.randomUUID().toString())
                .contextId(conversationId)
                .parts(List.<Part<?>>of(new TextPart(text)))
                .build();
        MessageSendParams params = MessageSendParams.builder().message(message).build();
        Object acceptHeader = context.headers().get("accept");
        boolean streaming = acceptHeader != null && acceptHeader.toString().contains("text/event-stream");
        return new A2ASendCommand(params, streaming);
    }

    @Override
    public Object fromA2ATask(Task task, Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", task.id());
        result.put("context_id", task.contextId());
        result.put("state", task.status() != null ? task.status().state() : null);
        return result;
    }

    @Override
    public SseEvent fromA2AStreamEvent(StreamingEventKind event, Context context) {
        return new SseEvent("message", event);
    }

    @Override
    public Object fromError(CustomRestError error, Context context) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("error", error.code());
        result.put("message", error.message());
        return result;
    }

    @Override
    public SseEvent fromStreamError(CustomRestError error, Context context) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", error.code());
        data.put("message", error.message());
        return new SseEvent("error", data);
    }
}
