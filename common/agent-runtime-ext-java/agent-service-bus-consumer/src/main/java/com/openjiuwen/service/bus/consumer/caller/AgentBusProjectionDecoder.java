/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Decodes the FEAT-017 response-projection descriptor carried by Agent Bus.
 *
 * @since 2026-08-04
 */
final class AgentBusProjectionDecoder {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    DecodedProjection decode(String inlinePayload) {
        Map<String, String> fields = fields(inlinePayload);
        String response = fields.get("a2aResponse");
        if (response == null) {
            return new DecodedProjection(fields, null, null);
        }
        String responseType = required(fields, "a2aResponseType");
        String json = decodeBase64Url(response);
        if (!"JsonRpcResponse".equals(responseType)) {
            throw new IllegalArgumentException("Unsupported a2aResponseType: " + responseType);
        }
        return decodeJsonRpc(fields, json);
    }

    private static DecodedProjection decodeJsonRpc(Map<String, String> fields, String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode result = root.path("result");
            JsonNode task = result.path("task");
            if (task.isMissingNode() && result.has("status")) {
                task = result;
            }
            if (!task.isMissingNode() && !task.isNull()) {
                return new DecodedProjection(fields, JsonUtil.fromJson(task.toString(), Task.class), null);
            }
            JsonNode message = result.path("message");
            if (!message.isMissingNode() && !message.isNull()) {
                return new DecodedProjection(fields, null,
                        JsonUtil.fromJson(message.toString(), Message.class));
            }
            throw new IllegalArgumentException("JSON-RPC response has no A2A Task or Message result");
        } catch (com.fasterxml.jackson.core.JsonProcessingException
                | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException failure) {
            throw new IllegalArgumentException("Invalid A2A JSON-RPC response projection", failure);
        }
    }

    private static Map<String, String> fields(String inlinePayload) {
        if (inlinePayload == null || inlinePayload.isBlank()) {
            throw new IllegalArgumentException("Response projection payload is empty");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String entry : inlinePayload.split(";")) {
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Invalid response projection field: " + entry);
            }
            result.put(entry.substring(0, separator), entry.substring(separator + 1));
        }
        return Map.copyOf(result);
    }

    private static String decodeBase64Url(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid Base64URL A2A response projection", failure);
        }
    }

    private static String required(Map<String, String> fields, String name) {
        String value = fields.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    record DecodedProjection(Map<String, String> fields, Task task, Message message) {
        String value(String name) {
            return fields.get(name);
        }
    }
}
