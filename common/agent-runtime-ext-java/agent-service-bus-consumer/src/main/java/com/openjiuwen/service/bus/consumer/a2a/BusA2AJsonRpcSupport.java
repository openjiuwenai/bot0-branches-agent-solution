/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.a2aproject.sdk.grpc.utils.JSONRPCUtils;
import org.a2aproject.sdk.jsonrpc.common.json.InvalidParamsJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.json.MethodNotFoundJsonMappingException;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2ARequest;
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

import java.util.Set;

/**
 * Strict JSON-RPC request boundary owned by the Agent Bus Runtime extension.
 *
 * <p>The Runtime HTTP controller deliberately keeps its existing parser. This component validates only
 * Bus-delivered A2A requests, retains their exact request id and delegates SDK parameter conversion to
 * {@link JSONRPCUtils}.
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
        Object originalId = validateEnvelope(root);
        String method = root.get("method").textValue();
        if (!SUPPORTED_METHODS.contains(method)) {
            throw failure(originalId,
                    new MethodNotFoundError(null, "Method not found: " + method, null));
        }

        normalizeMessageRequest(root, method);
        A2ARequest<?> sdkRequest;
        try {
            sdkRequest = JSONRPCUtils.parseRequestBody(write(root), expectedTenant);
        } catch (MethodNotFoundJsonMappingException exception) {
            throw failure(originalId,
                    new MethodNotFoundError(null, "Method not found: " + method, null), exception);
        } catch (InvalidParamsJsonMappingException exception) {
            throw failure(originalId, new InvalidParamsError("Invalid params"), exception);
        } catch (JsonMappingException exception) {
            throw failure(originalId, new InvalidRequestError("Invalid Request"), exception);
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException exception) {
            throw failure(originalId, new InvalidParamsError("Invalid params"), exception);
        }

        Object params = sdkRequest.getParams();
        validateRequiredParams(params, originalId);
        validateTenant(params, expectedTenant, originalId);
        return new ParsedA2ARequest(originalId, method, sdkRequest, params);
    }

    Object parseRequestId(String rawJson) {
        Object originalId = originalId(parseRoot(rawJson).get("id"));
        if (originalId == null) {
            throw failure(null, new InvalidRequestError("Invalid Request"));
        }
        return originalId;
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

    private static Object validateEnvelope(ObjectNode root) {
        JsonNode version = root.get("jsonrpc");
        JsonNode method = root.get("method");
        JsonNode params = root.get("params");
        Object originalId = originalId(root.get("id"));
        if (version == null || !version.isTextual() || !JSON_RPC_VERSION.equals(version.textValue())
                || method == null || !method.isTextual() || method.textValue().isBlank()
                || params == null || !params.isObject()
                || originalId == null) {
            throw failure(originalId, new InvalidRequestError("Invalid Request"));
        }
        return originalId;
    }

    private static Object originalId(JsonNode id) {
        if (id == null || id.isNull()) {
            return null;
        }
        if (id.isTextual()) {
            return id.textValue();
        }
        if (id.isIntegralNumber()) {
            return id.bigIntegerValue();
        }
        if (id.isFloatingPointNumber()) {
            return id.decimalValue();
        }
        return null;
    }

    private static void normalizeMessageRequest(ObjectNode root, String method) {
        if (!A2AMethods.SEND_MESSAGE_METHOD.equals(method)
                && !A2AMethods.SEND_STREAMING_MESSAGE_METHOD.equals(method)) {
            return;
        }
        ObjectNode params = (ObjectNode) root.get("params");
        JsonNode message = params.get("message");
        if (message instanceof ObjectNode messageObject) {
            JsonNode role = messageObject.get("role");
            if (role == null || role.isNull() || role.isTextual() && role.textValue().isBlank()) {
                messageObject.put("role", DEFAULT_MESSAGE_ROLE);
            }
            normalizeStandardPartKinds(messageObject);
        }
        normalizeInlinePushNotificationConfig(params);
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
        String actualTenant = null;
        if (params instanceof MessageSendParams value) {
            actualTenant = value.tenant();
        } else if (params instanceof TaskQueryParams value) {
            actualTenant = value.tenant();
        } else if (params instanceof TaskIdParams value) {
            actualTenant = value.tenant();
        }
        if (actualTenant == null || actualTenant.isBlank() || !expectedTenant.equals(actualTenant)) {
            throw failure(originalId, new InvalidParamsError("Invalid params: tenant does not match runtime"));
        }
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
        } else if (params instanceof TaskIdParams value && (value.id() == null || value.id().isBlank())) {
            throw failure(originalId, new InvalidParamsError("Invalid params: task id is required"));
        }
    }

    private static RequestException failure(Object id, A2AError error) {
        return new RequestException(id, error, null);
    }

    private static RequestException failure(Object id, A2AError error, Throwable cause) {
        return new RequestException(id, error, cause);
    }

    record ParsedA2ARequest(Object originalId, String method, A2ARequest<?> sdkRequest, Object params) {
    }

    static final class RequestException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final Object requestId;
        private final A2AError error;

        private RequestException(Object requestId, A2AError error, Throwable cause) {
            super(error.getMessage(), cause == null ? error : cause);
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
