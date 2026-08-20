/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.projection;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict FEAT-017 JSON response-projection codec shared by the Runtime producer and Caller. */
public final class AgentBusProjectionJsonCodec {
    public static final String SCHEMA_VERSION = "1.0";
    public static final int MAX_INLINE_BYTES = 64 * 1024;

    private static final Set<String> KINDS = Set.of("ACCEPTED", "REJECTED", "FAILED", "RESPONSE",
            "INPUT_REQUIRED", "STREAM_READY", "TERMINAL");
    private static final Set<String> STATUS = Set.of("input_required", "completed", "failed", "canceled",
            "rejected");
    private static final Set<String> TERMINAL_STATUS = Set.of("completed", "failed", "canceled", "rejected");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    /** Encodes and validates one canonical projection payload. */
    public String encode(BusResponseProjection projection) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("projectionKind", requireKind(projection.projectionKind()));
        if (projection.revision() < 0) {
            throw invalid("revision must be non-negative");
        }
        root.put("revision", projection.revision());
        putText(root, "taskId", projection.taskId());

        Map<String, Object> data = projection.data() == null ? Map.of() : projection.data();
        Object state = data.get("taskState");
        String status = state instanceof String text ? normalizeState(text) : text(data, "status");
        putText(root, "status", status);
        putText(root, "idempotencyResult", text(data, "idempotencyResult"));
        putText(root, "streamRef", text(data, "streamRef"));
        putText(root, "errorCode", text(data, "errorCode"));
        putText(root, "reason", text(data, "reason"));
        putBoolean(root, "retryable", data.get("retryable"));
        putA2aResponse(root, data.get("a2aResponse"));

        validate(root, projection.eventType());
        String json = write(root);
        if (json.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_BYTES) {
            throw invalid("projection payload exceeds 64 KiB");
        }
        return json;
    }

    /** Decodes and validates one JSON projection against its Bus event type. */
    public DecodedProjection decode(String inlinePayload, String eventType) {
        if (inlinePayload == null || inlinePayload.isBlank()) {
            throw invalid("projection payload is empty");
        }
        if (inlinePayload.getBytes(StandardCharsets.UTF_8).length > MAX_INLINE_BYTES) {
            throw invalid("projection payload exceeds 64 KiB");
        }
        JsonNode document;
        try {
            document = MAPPER.readTree(inlinePayload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid("projection payload is not strict JSON", exception);
        }
        if (!(document instanceof ObjectNode root)) {
            throw invalid("projection payload must be a JSON object");
        }
        validate(root, eventType);

        JsonNode response = root.get("a2aResponse");
        JsonNode result = response == null ? null : response.get("result");
        Task task = decodeTask(result);
        Message message = task == null ? decodeMessage(result) : null;
        return new DecodedProjection(text(root, "schemaVersion"), text(root, "projectionKind"),
                root.get("revision").longValue(), text(root, "taskId"), text(root, "status"),
                text(root, "idempotencyResult"), text(root, "streamRef"), text(root, "errorCode"),
                text(root, "reason"), bool(root, "retryable"), response,
                response == null ? null : response.get("id"),
                response == null ? null : response.get("error"), task, message);
    }

    private static void validate(ObjectNode root, String eventType) {
        String schema = requiredText(root, "schemaVersion");
        if (!"1".equals(schema.split("\\.", -1)[0])) {
            throw invalid("unsupported projection schema major version");
        }
        String kind = requireKind(requiredText(root, "projectionKind"));
        JsonNode revision = root.get("revision");
        if (revision == null || !revision.isIntegralNumber() || !revision.canConvertToLong()
                || revision.longValue() < 0) {
            throw invalid("revision must be a non-negative integer");
        }
        String expectedKind = kindForEvent(eventType);
        if (!kind.equals(expectedKind)) {
            throw invalid("projectionKind does not match eventType");
        }
        validateKnownTypes(root);
        validateKind(root, kind);
        validateJsonRpc(root);
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
            throw invalid("a2aResponse must be a JSON object");
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
                    throw invalid("TERMINAL requires a terminal status");
                }
                requiredObject(root, "a2aResponse");
            }
            default -> throw invalid("unsupported projectionKind");
        }
    }

    private static void validateJsonRpc(ObjectNode projection) {
        JsonNode responseNode = projection.get("a2aResponse");
        if (responseNode == null) {
            return;
        }
        ObjectNode response = (ObjectNode) responseNode;
        requireText(response, "jsonrpc", "2.0");
        JsonNode id = response.get("id");
        if (id == null || id.isNull() || !(id.isTextual() || id.isNumber())) {
            throw invalid("a2aResponse id must be a non-null string or number");
        }
        boolean hasResult = response.has("result") && !response.get("result").isNull();
        boolean hasError = response.has("error") && !response.get("error").isNull();
        if (hasResult == hasError) {
            throw invalid("a2aResponse must contain exactly one of result or error");
        }
        if (hasError && !response.get("error").isObject()) {
            throw invalid("a2aResponse error must be an object");
        }
        if (hasError) {
            validateJsonRpcError((ObjectNode) response.get("error"));
        }
        String projectionTaskId = text(projection, "taskId");
        String responseTaskId = responseTaskId(response.get("result"));
        if (responseTaskId != null && projectionTaskId != null && !projectionTaskId.equals(responseTaskId)) {
            throw invalid("a2aResponse Task id does not match projection taskId");
        }
        if (responseTaskId != null && projectionTaskId == null
                && "RESPONSE".equals(text(projection, "projectionKind"))) {
            throw invalid("RESPONSE carrying a Task requires taskId");
        }
    }

    private static void validateJsonRpcError(ObjectNode error) {
        JsonNode code = error.get("code");
        JsonNode message = error.get("message");
        if (code == null || !code.isIntegralNumber() || message == null || !message.isTextual()
                || message.textValue().isBlank()) {
            throw invalid("a2aResponse error requires integer code and non-blank message");
        }
    }

    private static Task decodeTask(JsonNode result) {
        if (result == null || !result.isObject()) {
            return null;
        }
        JsonNode candidate = result.get("task");
        if (candidate == null && result.has("status") && result.has("id")) {
            candidate = result;
        }
        if (candidate == null || candidate.isNull()) {
            return null;
        }
        try {
            return JsonUtil.fromJson(candidate.toString(), Task.class);
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException exception) {
            throw invalid("invalid A2A Task result", exception);
        }
    }

    private static Message decodeMessage(JsonNode result) {
        if (result == null || !result.isObject()) {
            return null;
        }
        JsonNode candidate = result.get("message");
        if (candidate == null && result.has("role") && result.has("parts")) {
            candidate = result;
        }
        if (candidate == null || candidate.isNull()) {
            return null;
        }
        try {
            return JsonUtil.fromJson(candidate.toString(), Message.class);
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException exception) {
            throw invalid("invalid A2A Message result", exception);
        }
    }

    private static void putA2aResponse(ObjectNode root, Object value) {
        if (value == null) {
            return;
        }
        JsonNode response;
        try {
            if (value instanceof String json) {
                response = MAPPER.readTree(json);
            } else if (value instanceof JsonNode node) {
                response = node;
            } else {
                response = MAPPER.valueToTree(value);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid("a2aResponse is not valid JSON", exception);
        }
        root.set("a2aResponse", response);
    }

    private static String responseTaskId(JsonNode result) {
        if (result == null || !result.isObject()) {
            return null;
        }
        JsonNode task = result.has("task") ? result.get("task") : result;
        JsonNode id = task == null ? null : task.get("id");
        return id != null && id.isTextual() && !id.textValue().isBlank() ? id.textValue() : null;
    }

    private static String kindForEvent(String eventType) {
        if (eventType == null) {
            throw invalid("eventType is required");
        }
        for (String kind : Set.of("INPUT_REQUIRED", "STREAM_READY", "ACCEPTED", "REJECTED", "FAILED",
                "RESPONSE", "TERMINAL")) {
            if (eventType.endsWith("_" + kind)) {
                return kind;
            }
        }
        throw invalid("unsupported projection eventType");
    }

    private static String normalizeState(String state) {
        String normalized = state.toUpperCase(Locale.ROOT).startsWith("TASK_STATE_")
                ? state.substring("TASK_STATE_".length()) : state;
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String requireKind(String kind) {
        if (kind == null || !KINDS.contains(kind)) {
            throw invalid("unsupported projectionKind: " + kind);
        }
        return kind;
    }

    private static void putText(ObjectNode root, String name, String value) {
        if (value != null && !value.isBlank()) {
            root.put(name, value);
        }
    }

    private static void putBoolean(ObjectNode root, String name, Object value) {
        if (value == null) {
            return;
        }
        if (!(value instanceof Boolean bool)) {
            throw invalid(name + " must be a boolean");
        }
        root.put(name, bool);
    }

    private static String text(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw invalid(name + " must be a string");
        }
        return text;
    }

    private static String text(ObjectNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : value.textValue();
    }

    private static Boolean bool(ObjectNode root, String name) {
        JsonNode value = root.get(name);
        return value == null || value.isNull() ? null : value.booleanValue();
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

    private static Boolean requiredBoolean(ObjectNode root, String name) {
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

    private static String write(ObjectNode root) {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw invalid("failed to encode projection", exception);
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("PROJECTION_PAYLOAD_INVALID: " + message);
    }

    private static IllegalArgumentException invalid(String message, Throwable cause) {
        return new IllegalArgumentException("PROJECTION_PAYLOAD_INVALID: " + message, cause);
    }

    /** Typed projection returned only after envelope and embedded JSON-RPC validation. */
    public record DecodedProjection(String schemaVersion, String projectionKind, long revision, String taskId,
            String status, String idempotencyResult, String streamRef, String errorCode, String reason,
            Boolean retryable, JsonNode a2aResponse, JsonNode responseId, JsonNode responseError,
            Task task, Message message) {
    }
}
