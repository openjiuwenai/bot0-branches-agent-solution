/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds forwarded {@link ServeRequest} instances for cross-layer A2A calls.
 *
 * <p>Shared by {@link A2AGatewayRemoteAgentCaller} (production form) and
 * {@link InProcessRemoteAgentCaller} (dev/test form) to append the upstream
 * {@code response_content} as an {@code assistant} message to the messages
 * array while preserving {@code lastUserQuery()} (per L2 §4.9.3).
 *
 * @since 0.1.0
 */
final class ForwardedServeRequests {
    private ForwardedServeRequests() {
    }

    /**
     * Constructs a new {@link ServeRequest} carrying the original conversation
     * context with the upstream {@code responseContent} appended as an
     * assistant message.
     *
     * @param original        the original serve request
     * @param responseContent optional upstream response content; when blank no
     *                        assistant message is appended
     * @return a new serve request with messages appended
     */
    static ServeRequest build(ServeRequest original, String responseContent) {
        ServeRequest forwarded = new ServeRequest();
        forwarded.setConversationId(original.getConversationId());
        forwarded.setUserId(original.getUserId());
        forwarded.setSpaceId(original.getSpaceId());
        forwarded.setTenantId(original.getTenantId());
        forwarded.setStream(original.isStream());
        forwarded.setMetadata(original.getMetadata());
        List<Map<String, Object>> messages = new ArrayList<>(original.getMessages());
        if (responseContent != null && !responseContent.isBlank()) {
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", responseContent);
            messages.add(assistant);
        }
        forwarded.setMessages(messages);
        return forwarded;
    }
}
