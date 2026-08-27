/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.JSONParseError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TextPart;

import java.util.Optional;
import java.util.Set;

/**
 * Strict JSON-RPC request boundary owned by the Agent Bus Runtime extension.
 *
 * <p>The Runtime HTTP controller deliberately keeps its existing parser. This component validates only
 * Bus-delivered A2A requests, retains their exact request id and converts method parameters with the
 * transport-neutral JSON utilities from the A2A SDK.
 */
final class BusA2AJsonRpcSupport {
    private static final String JSON_RPC_VERSION = "2.0";
    private static final String DEFAULT_MESSAGE_ROLE = "ROLE_USER";

    private static final Set<String> SUPPORTED_METHODS = Set.of(
            A2AMethods.SEND_MESSAGE_METHOD,
            A2AMethods.SEND_STREAMING_MESSAGE_METHOD,
            A2AMethods.GET_TASK_METHOD,
            A2AMethods.SUBSCRIBE_TO_TASK_METHOD);

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    ParsedA2ARequest parseRequest(String rawJson, String expectedTenant) {
        ObjectNode root = parseRoot(rawJson);
        ValidatedEnvelope envelope = validateEnvelope(root);
        Object originalId = envelope.originalId();
        String method = envelope.method();
        if (!SUPPORTED_METHODS.contains(method)) {
            throw failure(originalId,
                    new MethodNotFoundError(null, "Method not found: " + method, null));
        }

        normalizeMessageRequest(envelope.params(), method, originalId);
        Object params = parseParams(envelope.params(), method, expectedTenant, originalId);
        validateRequiredParams(params, originalId);
        validateTenant(params, expectedTenant, originalId);
        return new ParsedA2ARequest(originalId, method, params);
    }

    Object parseRequestId(String rawJson) {
        return originalId(parseRoot(rawJson).get("id"))
                .orElseThrow(() -> failure(null, new InvalidRequestError("Invalid Request")));
    }

    private static ObjectNode parseRoot(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            throw failure(null, new JSONParseError("Parse error"));
        }
        JsonNode document;
        try {
            document = STRICT_MAPPER.readTree(rawJson);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw failure(null, new JSONParseError("Parse error"), exception);
        }
        if (!(document instanceof ObjectNode object)) {
            throw failure(null, new InvalidRequestError("Invalid Request"));
        }
        return object;
    }

    private static ValidatedEnvelope validateEnvelope(ObjectNode root) {
        Optional<Object> originalId = originalId(root.get("id"));
        Object errorId = originalId.orElse(null);
        JsonNode version = root.get("jsonrpc");
        if (version == null || !version.isTextual() || !JSON_RPC_VERSION.equals(version.textValue())) {
            throw failure(errorId, new InvalidRequestError("Invalid Request"));
        }
        JsonNode method = root.get("method");
        if (method == null || !method.isTextual() || method.textValue().isBlank()) {
            throw failure(errorId, new InvalidRequestError("Invalid Request"));
        }
        JsonNode params = root.get("params");
        if (!(params instanceof ObjectNode paramsObject)) {
            throw failure(errorId, new InvalidRequestError("Invalid Request"));
        }
        return new ValidatedEnvelope(
                originalId.orElseThrow(() -> failure(null, new InvalidRequestError("Invalid Request"))),
                method.textValue(), paramsObject);
    }

    private static Optional<Object> originalId(JsonNode id) {
        if (id == null || id.isNull()) {
            return Optional.empty();
        }
        if (id.isTextual()) {
            return Optional.of(id.textValue());
        }
        if (id.isIntegralNumber()) {
            return Optional.of(id.bigIntegerValue());
        }
        if (id.isFloatingPointNumber()) {
            return Optional.of(id.decimalValue());
        }
        return Optional.empty();
    }

    private static void normalizeMessageRequest(ObjectNode params, String method, Object originalId) {
        if (!A2AMethods.SEND_MESSAGE_METHOD.equals(method)
                && !A2AMethods.SEND_STREAMING_MESSAGE_METHOD.equals(method)) {
            return;
        }
        JsonNode message = params.get("message");
        if (message instanceof ObjectNode messageObject) {
            JsonNode role = messageObject.get("role");
            boolean roleMissing = role == null || role.isNull()
                    || role.isTextual() && role.textValue().isBlank();
            if (!roleMissing && (!role.isTextual() || !isMessageRole(role.textValue()))) {
                throw failure(originalId, new InvalidParamsError("Invalid params: message role is invalid"));
            }
            if (roleMissing) {
                messageObject.put("role", DEFAULT_MESSAGE_ROLE);
            }
            normalizeStandardPartKinds(messageObject);
        }
        normalizeInlinePushNotificationConfig(params);
    }

    private static boolean isMessageRole(String role) {
        return "ROLE_USER".equals(role) || "ROLE_AGENT".equals(role) || "ROLE_UNSPECIFIED".equals(role);
    }

    private static Object parseParams(ObjectNode params, String method, String expectedTenant, Object originalId) {
        validateKnownFields(params, method, originalId);
        try {
            return switch (method) {
                case A2AMethods.SEND_MESSAGE_METHOD, A2AMethods.SEND_STREAMING_MESSAGE_METHOD ->
                    withTenant(JsonUtil.fromJson(write(params), MessageSendParams.class), expectedTenant);
                case A2AMethods.GET_TASK_METHOD ->
                    withTenant(JsonUtil.fromJson(write(params), TaskQueryParams.class), expectedTenant);
                case A2AMethods.SUBSCRIBE_TO_TASK_METHOD ->
                    withTenant(JsonUtil.fromJson(write(params), TaskIdParams.class), expectedTenant);
                default -> throw failure(originalId,
                        new MethodNotFoundError(null, "Method not found: " + method, null));
            };
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw failure(originalId, new InvalidParamsError("Invalid params"), exception);
        }
    }

    private static void validateKnownFields(ObjectNode params, String method, Object originalId) {
        Set<String> allowedFields = switch (method) {
            case A2AMethods.SEND_MESSAGE_METHOD, A2AMethods.SEND_STREAMING_MESSAGE_METHOD ->
                Set.of("message", "configuration", "metadata", "tenant");
            case A2AMethods.GET_TASK_METHOD -> Set.of("id", "historyLength", "tenant");
            case A2AMethods.SUBSCRIBE_TO_TASK_METHOD -> Set.of("id", "tenant");
            default -> Set.of();
        };
        params.fieldNames().forEachRemaining(field -> {
            if (!allowedFields.contains(field)) {
                throw failure(originalId, new InvalidParamsError("Invalid params: unknown field " + field));
            }
        });
    }

    private static MessageSendParams withTenant(MessageSendParams params, String expectedTenant) {
        if (expectedTenant == null || expectedTenant.isBlank()
                || params.tenant() != null && !params.tenant().isBlank()) {
            return params;
        }
        return new MessageSendParams(params.message(), params.configuration(), params.metadata(), expectedTenant);
    }

    private static TaskQueryParams withTenant(TaskQueryParams params, String expectedTenant) {
        if (expectedTenant == null || expectedTenant.isBlank()
                || params.tenant() != null && !params.tenant().isBlank()) {
            return params;
        }
        return new TaskQueryParams(params.id(), params.historyLength(), expectedTenant);
    }

    private static TaskIdParams withTenant(TaskIdParams params, String expectedTenant) {
        if (expectedTenant == null || expectedTenant.isBlank()
                || params.tenant() != null && !params.tenant().isBlank()) {
            return params;
        }
        return new TaskIdParams(params.id(), expectedTenant);
    }

    private static void normalizeStandardPartKinds(ObjectNode message) {
        JsonNode parts = message.get("parts");
        if (parts == null || !parts.isArray()) {
            return;
        }
        for (JsonNode part : parts) {
            if (!(part instanceof ObjectNode partObject)) {
                continue;
            }
            JsonNode kind = partObject.get("kind");
            if (kind != null && kind.isTextual()
                    && Set.of("text", "data", "file").contains(kind.textValue().toLowerCase())) {
                partObject.remove("kind");
            }
        }
    }

    private static void normalizeInlinePushNotificationConfig(ObjectNode params) {
        JsonNode inline = params.remove("pushNotificationConfig");
        if (inline == null || inline.isNull()) {
            return;
        }
        ObjectNode configuration;
        JsonNode currentConfiguration = params.get("configuration");
        if (currentConfiguration == null || currentConfiguration.isNull()) {
            configuration = params.putObject("configuration");
        } else if (currentConfiguration instanceof ObjectNode object) {
            configuration = object;
        } else {
            return;
        }
        JsonNode normalized = inline.deepCopy();
        if (normalized instanceof ObjectNode pushConfig) {
            JsonNode callbackUrl = pushConfig.remove("callbackUrl");
            if (callbackUrl != null && !pushConfig.has("url")) {
                pushConfig.set("url", callbackUrl);
            }
        }
        configuration.set("taskPushNotificationConfig", normalized);
        configuration.put("returnImmediately", true);
    }

    private static String write(ObjectNode root) {
        try {
            return STRICT_MAPPER.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw failure(null, new InvalidRequestError("Invalid Request"), exception);
        }
    }

    private static void validateTenant(Object params, String expectedTenant, Object originalId) {
        if (expectedTenant == null || expectedTenant.isBlank()) {
            return;
        }
        Optional<String> actualTenant = tenant(params);
        if (actualTenant.filter(expectedTenant::equals).isEmpty()) {
            throw failure(originalId, new InvalidParamsError("Invalid params: tenant does not match runtime"));
        }
    }

    private static Optional<String> tenant(Object params) {
        if (params instanceof MessageSendParams value) {
            return nonBlank(value.tenant());
        }
        if (params instanceof TaskQueryParams value) {
            return nonBlank(value.tenant());
        }
        if (params instanceof TaskIdParams value) {
            return nonBlank(value.tenant());
        }
        return Optional.empty();
    }

    private static Optional<String> nonBlank(String value) {
        return Optional.ofNullable(value).filter(candidate -> !candidate.isBlank());
    }

    private static void validateRequiredParams(Object params, Object originalId) {
        if (params instanceof MessageSendParams value) {
            if (value.message() == null || value.message().parts() == null || value.message().parts().isEmpty()) {
                throw failure(originalId, new InvalidParamsError("Invalid params: message parts are required"));
            }
            boolean usableText = value.message().parts().stream()
                    .anyMatch(part -> part instanceof TextPart textPart && !textPart.text().isBlank());
            if (!usableText) {
                throw failure(originalId,
                        new InvalidParamsError("Invalid params: message parts require non-blank text"));
            }
            return;
        }
        if (params instanceof TaskQueryParams value) {
            if (value.id() == null || value.id().isBlank()) {
                throw failure(originalId, new InvalidParamsError("Invalid params: task id is required"));
            }
            return;
        }
        if (params instanceof TaskIdParams value && (value.id() == null || value.id().isBlank())) {
            throw failure(originalId, new InvalidParamsError("Invalid params: task id is required"));
        }
    }

    private static RequestException failure(Object id, A2AError error) {
        return new RequestException(id, error);
    }

    private static RequestException failure(Object id, A2AError error, Throwable cause) {
        return new RequestException(id, error, cause);
    }

    record ParsedA2ARequest(Object originalId, String method, Object params) {
    }

    private record ValidatedEnvelope(Object originalId, String method, ObjectNode params) {
    }

    static final class RequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Object requestId;
        private final A2AError error;

        private RequestException(Object requestId, A2AError error) {
            super(error.getMessage(), error);
            this.requestId = requestId;
            this.error = error;
        }

        private RequestException(Object requestId, A2AError error, Throwable cause) {
            super(error.getMessage(), cause);
            this.requestId = requestId;
            this.error = error;
        }

        Object getRequestId() {
            return requestId;
        }

        A2AError getError() {
            return error;
        }
    }
}
