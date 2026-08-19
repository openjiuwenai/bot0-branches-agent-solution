/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

/**
 * JSON-RPC 2.0 error envelope for method-result errors (A1, FEAT-011 L2 §8.5.3).
 *
 * <p>Method-result errors (UNKNOWN / TASK_NOT_FOUND / CONTINUATION_FAILED /
 * STREAM_NOT_AVAILABLE / PAYLOAD_REF_UNAVAILABLE / passthrough runtime errors)
 * surface as HTTP 200 + this envelope, not as non-2xx + flat {@link GatewayError}.
 * The envelope carries:
 * <ul>
 *   <li>{@code error.code} — numeric JSON-RPC code (e.g. -32050)</li>
 *   <li>{@code error.message} — human-readable message</li>
 *   <li>{@code error.data.code} — stable string code (e.g. "PROJECTION_TIMEOUT_UNKNOWN")</li>
 *   <li>{@code error.data.retryable} — whether the client should retry</li>
 * </ul>
 *
 * <p>Runtime passthrough errors are re-wrapped: runtime emits only
 * {@code {code, message}} (no {@code data} field — R12 confirmed); gateway
 * enriches with {@code data.code}/{@code retryable} via
 * {@link ErrorCodes#fromRpcCode(int)}.
 *
 * @param jsonrpc always "2.0"
 * @param id JSON-RPC request id (matches the client request; may be null if unknown)
 * @param error the error object
 * @since 0.1.0
 */
public record JsonRpcError(String jsonrpc, String id, Error error) {

    /**
     * Construct a JsonRpcError from an ErrorCodes constant.
     *
     * @param requestId JSON-RPC request id (may be null)
     * @param ec stable error code constant
     * @param message human-readable message
     * @return a complete JsonRpcError envelope
     */
    public static JsonRpcError of(String requestId, ErrorCodes ec, String message) {
        return new JsonRpcError("2.0", requestId,
                new Error(ec.numericCode(), message,
                        new ErrorData(ec.stableCode(), ec.retryable())));
    }

    /**
     * The JSON-RPC error object.
     *
     * @param code numeric JSON-RPC error code
     * @param message human-readable message
     * @param data stable-code + retryable metadata (may be null for raw passthrough)
     */
    public record Error(int code, String message, ErrorData data) {
    }

    /**
     * Stable-code metadata placed in {@code error.data}.
     *
     * @param code stable string code (e.g. "PROJECTION_TIMEOUT_UNKNOWN")
     * @param retryable whether the client should retry
     */
    public record ErrorData(String code, boolean retryable) {
    }
}
