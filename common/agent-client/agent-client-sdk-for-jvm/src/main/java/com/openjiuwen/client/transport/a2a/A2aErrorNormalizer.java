/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.openjiuwen.client.api.ErrorCodes;

import java.util.Optional;

/** Shared normalization for HTTP and JSON-RPC A2A errors. */
final class A2aErrorNormalizer {
    private A2aErrorNormalizer() {
    }

    static Optional<A2aTransportException> fromJsonRpc(JsonNode root) {
        JsonNode error = root == null ? null : root.get("error");
        if (error == null || error.isNull()) {
            return Optional.empty();
        }
        int rpcCode = error.path("code").asInt();
        String message = error.path("message").asText("");
        String declared = error.path("data").path("code").asText(null);
        String code = normalizeCode(rpcCode, message, declared);
        return Optional.of(new A2aTransportException("JSON-RPC error: " + rpcCode + " " + message,
                null, code, 0, false));
    }

    static A2aTransportException fromHttp(int status, JsonNode body, String rawBody) {
        Optional<A2aTransportException> rpc = fromJsonRpc(body);
        if (rpc.isPresent()) {
            A2aTransportException normalized = rpc.get();
            // HTTP status remains authoritative for infrastructure failures even
            // when the body happens to use JSON-RPC. A deterministic JSON-RPC
            // error on a successful (2xx) response stays non-retryable.
            boolean httpRetryable = status == 408 || status == 425 || status == 429 || status >= 500;
            return new A2aTransportException(normalized.getMessage(), normalized, normalized.code(), status,
                    normalized.retryable() || httpRetryable);
        }
        String code = body == null ? null : body.path("code").asText(null);
        String message = body == null ? rawBody : body.path("message").asText(rawBody);
        String resolved = code == null || code.isBlank() ? ErrorCodes.fromHttpStatus(status) : code;
        return A2aTransportException.governance(
                "gateway rejected request [" + status + "/" + resolved + "]: " + message,
                resolved, status);
    }

    private static String normalizeCode(int rpcCode, String message, String declared) {
        if (declared != null && !declared.isBlank()) {
            if ("CURSOR_EXPIRED".equals(declared)
                    || ErrorCodes.REPLAY_CURSOR_EXPIRED.equals(declared)) {
                return ErrorCodes.REPLAY_CURSOR_EXPIRED;
            }
            if ("TASK_NOT_SUBSCRIBABLE_TERMINAL".equals(declared)
                    || ErrorCodes.SUBSCRIPTION_UNAVAILABLE.equals(declared)
                    || "UNSUPPORTED_OPERATION".equals(declared)) {
                return ErrorCodes.SUBSCRIPTION_UNAVAILABLE;
            }
            return declared;
        }
        String lower = message == null ? "" : message.toLowerCase(java.util.Locale.ROOT);
        if (rpcCode == -32602 && lower.contains("invalid task state for subscription")) {
            return ErrorCodes.SUBSCRIPTION_UNAVAILABLE;
        }
        return switch (rpcCode) {
            case -32601 -> ErrorCodes.METHOD_NOT_SUPPORTED;
            case -32602 -> ErrorCodes.VALIDATION_FAILED;
            case -32001 -> ErrorCodes.TASK_NOT_FOUND;
            case -32004 -> ErrorCodes.SUBSCRIPTION_UNAVAILABLE;
            default -> "JSONRPC_" + rpcCode;
        };
    }
}
