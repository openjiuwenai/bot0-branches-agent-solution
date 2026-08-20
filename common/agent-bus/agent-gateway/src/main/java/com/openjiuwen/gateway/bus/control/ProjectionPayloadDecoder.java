/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Strict Gateway decoder for the FEAT-017 JSON projection envelope. */
final class ProjectionPayloadDecoder {
    private static final Set<String> KINDS = Set.of("ACCEPTED", "REJECTED", "FAILED", "RESPONSE",
            "INPUT_REQUIRED", "STREAM_READY", "TERMINAL");
    private static final Set<String> STATUS = Set.of("input_required", "completed", "failed", "canceled",
            "rejected");
    private static final Set<String> TERMINAL_STATUS = Set.of("completed", "failed", "canceled", "rejected");
    private static final int MAX_INLINE_BYTES = 64 * 1024;
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    DecodedProjection decode(String payload, AgentBusEventType eventType) {
        if (payload == null || payload.isBlank()) {
            throw invalid("projection payload is empty");
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_BYTES) {
            throw invalid("projection payload exceeds 64 KiB");
        }
        JsonNode document;
        try {
            document = MAPPER.readTree(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid("projection payload is not strict JSON", exception);
        }
        if (!(document instanceof ObjectNode root)) {
            throw invalid("projection payload must be a JSON object");
        }
        String schema = requiredText(root, "schemaVersion");
        if (!"1".equals(schema.split("\\.", -1)[0])) {
            throw invalid("unsupported schema major version");
        }
        String kind = requiredText(root, "projectionKind");
        if (!KINDS.contains(kind) || !kind.equals(kindForEvent(eventType))) {
            throw invalid("projectionKind does not match eventType");
        }
        JsonNode revision = root.get("revision");
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()
                || revision.longValue() < 0) {
            throw invalid("revision must be a non-negative integer");
        }
        validateKnownTypes(root);
        validateKind(root, kind);
        JsonNode response = validateResponse(root);
        String body = response == null ? firstNonBlank(text(root, "reason"), text(root, "errorCode"))
                : write(response);
        return new DecodedProjection(kind, text(root, "taskId"), text(root, "streamRef"), body,
                response != null);
    }

    private static void validateKnownTypes(ObjectNode root) {
        for (String name : Set.of("taskId", "status", "idempotencyResult", "streamRef", "errorCode", "reason")) {
            JsonNode value = root.get(name);
            if (value != null && (!value.isTextual() || value.textValue().isBlank())) {
                throw invalid(name + " must be a non-blank string");
            }
        }
        JsonNode retryable = root.get("retryable");
        if (retryable != null && !retryable.isBoolean()) {
            throw invalid("retryable must be a boolean");
        }
        JsonNode response = root.get("a2aResponse");
        if (response != null && !response.isObject()) {
            throw invalid("a2aResponse must be an object");
        }
        String status = text(root, "status");
        if (status != null && !STATUS.contains(status)) {
            throw invalid("status is not a supported wire value");
        }
    }

    private static void validateKind(ObjectNode root, String kind) {
        switch (kind) {
            case "ACCEPTED" -> {
                requiredText(root, "taskId");
                requiredText(root, "idempotencyResult");
            }
            case "REJECTED" -> {
                if (blank(root, "errorCode") && blank(root, "reason")) {
                    throw invalid("REJECTED requires errorCode or reason");
                }
                requireBoolean(root, "retryable", false);
            }
            case "FAILED" -> {
                requiredText(root, "errorCode");
                requiredBoolean(root, "retryable");
            }
            case "RESPONSE" -> requiredObject(root, "a2aResponse");
            case "INPUT_REQUIRED" -> {
                requiredText(root, "taskId");
                requireText(root, "status", "input_required");
                requiredObject(root, "a2aResponse");
            }
            case "STREAM_READY" -> {
                requiredText(root, "taskId");
                requiredText(root, "streamRef");
            }
            case "TERMINAL" -> {
                requiredText(root, "taskId");
                if (!TERMINAL_STATUS.contains(requiredText(root, "status"))) {
                    throw invalid("TERMINAL requires terminal status");
                }
                requiredObject(root, "a2aResponse");
            }
            default -> throw invalid("unsupported projectionKind");
        }
    }

    private static JsonNode validateResponse(ObjectNode projection) {
        JsonNode responseNode = projection.get("a2aResponse");
        if (responseNode == null) {
            return null;
        }
        ObjectNode response = (ObjectNode) responseNode;
        requireText(response, "jsonrpc", "2.0");
        JsonNode id = response.get("id");
        if (id == null || id.isNull() || !(id.isTextual() || id.isNumber())) {
            throw invalid("a2aResponse id must be a non-null string or number");
        }
        boolean result = response.has("result") && !response.get("result").isNull();
        boolean error = response.has("error") && !response.get("error").isNull();
        if (result == error) {
            throw invalid("a2aResponse must contain exactly one of result or error");
        }
        if (error && !response.get("error").isObject()) {
            throw invalid("a2aResponse error must be an object");
        }
        if (error) {
            validateJsonRpcError((ObjectNode) response.get("error"));
        }
        String responseTaskId = responseTaskId(response.get("result"));
        String taskId = text(projection, "taskId");
        if (responseTaskId != null && taskId != null && !taskId.equals(responseTaskId)) {
            throw invalid("a2aResponse Task id does not match projection taskId");
        }
        if (responseTaskId != null && taskId == null
                && "RESPONSE".equals(text(projection, "projectionKind"))) {
            throw invalid("RESPONSE carrying a Task requires taskId");
        }
        return response;
    }

    private static void validateJsonRpcError(ObjectNode error) {
        JsonNode code = error.get("code");
        JsonNode message = error.get("message");
        if (code == null || !code.isIntegralNumber() || message == null || !message.isTextual()
                || message.textValue().isBlank()) {
            throw invalid("a2aResponse error requires integer code and non-blank message");
        }
    }

    private static String responseTaskId(JsonNode result) {
        if (result == null || !result.isObject()) {
            return null;
        }
        JsonNode task = result.has("task") ? result.get("task") : result;
        JsonNode id = task == null ? null : task.get("id");
        return id != null && id.isTextual() && !id.textValue().isBlank() ? id.textValue() : null;
    }

    private static String kindForEvent(AgentBusEventType eventType) {
        if (eventType == null) {
            throw invalid("eventType is required");
        }
        String name = eventType.name();
        for (String kind : Set.of("INPUT_REQUIRED", "STREAM_READY", "ACCEPTED", "REJECTED", "FAILED",
                "RESPONSE", "TERMINAL")) {
            if (name.endsWith("_" + kind)) {
                return kind;
            }
        }
        throw invalid("unsupported eventType");
    }

    private static String text(ObjectNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : value.textValue();
    }

    private static String requiredText(ObjectNode root, String name) {
        String value = text(root, name);
        if (value == null || value.isBlank()) {
            throw invalid(name + " is required");
        }
        return value;
    }

    private static void requireText(ObjectNode root, String name, String expected) {
        if (!expected.equals(requiredText(root, name))) {
            throw invalid(name + " must equal " + expected);
        }
    }

    private static ObjectNode requiredObject(ObjectNode root, String name) {
        JsonNode value = root.get(name);
        if (!(value instanceof ObjectNode object)) {
            throw invalid(name + " is required and must be an object");
        }
        return object;
    }

    private static boolean requiredBoolean(ObjectNode root, String name) {
        JsonNode value = root.get(name);
        if (value == null || !value.isBoolean()) {
            throw invalid(name + " is required and must be a boolean");
        }
        return value.booleanValue();
    }

    private static void requireBoolean(ObjectNode root, String name, boolean expected) {
        if (requiredBoolean(root, name) != expected) {
            throw invalid(name + " must equal " + expected);
        }
    }

    private static boolean blank(ObjectNode root, String name) {
        String value = text(root, name);
        return value == null || value.isBlank();
    }

    private static String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private static String write(JsonNode value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid("failed to serialize a2aResponse", exception);
        }
    }

    private static ProjectionProtocolException invalid(String message) {
        return new ProjectionProtocolException(message);
    }

    private static ProjectionProtocolException invalid(String message, Throwable cause) {
        return new ProjectionProtocolException(message, cause);
    }

    record DecodedProjection(String projectionKind, String taskId, String streamRef, String body,
            boolean a2aResponsePresent) {
    }
}
