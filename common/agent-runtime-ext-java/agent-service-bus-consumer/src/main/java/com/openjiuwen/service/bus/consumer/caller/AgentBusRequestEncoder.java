/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Encodes a Runtime remote call as a standard A2A JSON-RPC request.
 *
 * @since 2026-08-04
 */
final class AgentBusRequestEncoder {
    private final ObjectMapper mapper = new ObjectMapper();

    String encode(RemoteCall call, String requestId, String tenantId) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "ROLE_USER");
        message.put("messageId", requestId);
        message.put("contextId", contextId(call, requestId));
        if (call.taskId() != null && !call.taskId().isBlank()) {
            message.put("taskId", call.taskId());
        }
        message.put("parts", List.of(Map.of("text", require(call.message(), "message"))));
        if (!call.messageMetadata().isEmpty()) {
            message.put("metadata", call.messageMetadata());
        }

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("message", message);
        params.put("configuration", Map.of("returnImmediately", false));
        if (!call.metadata().isEmpty()) {
            params.put("metadata", call.metadata());
        }
        params.put("tenant", tenantId);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", requestId);
        request.put("method", call.isCallerStreaming() ? "SendStreamingMessage" : "SendMessage");
        request.put("params", params);
        try {
            return mapper.writeValueAsString(request);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Remote call cannot be encoded as A2A JSON-RPC", failure);
        }
    }

    private static String contextId(RemoteCall call, String fallback) {
        return call.contextId() == null || call.contextId().isBlank() ? fallback : call.contextId();
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
