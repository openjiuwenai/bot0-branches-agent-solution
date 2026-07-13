/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import com.openjiuwen.service.spec.dto.ServeRequest;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class AgentScopeRequestMapper {
    RuntimeContext mapContext(ServeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        RuntimeContext.Builder builder = RuntimeContext.builder()
            .sessionId(conversationId)
            .userId(request.getUserId());
        putIfPresent(builder, "tenantId", request.getTenantId());
        putIfPresent(builder, "spaceId", request.getSpaceId());
        Map<String, Object> metadata = request.getMetadata();
        if (metadata != null) {
            putIfPresent(builder, "traceId", metadata.get("traceId"));
            putIfPresent(builder, "requestId", metadata.get("requestId"));
        }
        return builder.build();
    }

    List<Msg> mapCurrentTurn(ServeRequest request) {
        return List.of(new UserMessage(latestEffectiveContent(request)));
    }

    static String latestEffectiveContent(ServeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        List<Map<String, Object>> messages = request.getMessages();
        String fallback = null;
        if (messages != null) {
            for (int index = messages.size() - 1; index >= 0; index--) {
                Map<String, Object> message = messages.get(index);
                String content = effectiveContent(message);
                if (content == null) {
                    continue;
                }
                if (fallback == null) {
                    fallback = content;
                }
                Object role = message.get("role");
                if (role != null && "user".equalsIgnoreCase(String.valueOf(role))) {
                    return content;
                }
            }
        }
        if (fallback != null) {
            return fallback;
        }
        throw new IllegalArgumentException("request must contain an effective message");
    }

    private static String effectiveContent(Map<String, Object> message) {
        if (message == null || message.get("content") == null) {
            return null;
        }
        String content = String.valueOf(message.get("content"));
        return content.isBlank() ? null : content;
    }

    private static void putIfPresent(RuntimeContext.Builder builder, String key, Object value) {
        if (value != null) {
            builder.put(key, value);
        }
    }
}
