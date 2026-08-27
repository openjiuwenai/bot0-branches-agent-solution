/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

/**
 * Thrown when a method-result error should surface as HTTP 200 + JSON-RPC
 * {@code error} envelope (A1, FEAT-011 L2 §8.5.3). This is distinct from
 * {@link GovernanceException} (which surfaces as non-2xx + flat
 * {@link GatewayError}).
 *
 * <p>Method-result errors include:
 * <ul>
 *   <li>{@link ErrorCodes#PROJECTION_TIMEOUT_UNKNOWN} — S9 accept-window timeout</li>
 *   <li>{@link ErrorCodes#CONTINUATION_FAILED} — sticky miss / no routable owner</li>
 *   <li>{@link ErrorCodes#STREAM_NOT_AVAILABLE} — S8 task not subscribable</li>
 *   <li>{@link ErrorCodes#PAYLOAD_REF_UNAVAILABLE} — S6 payloadRef transient</li>
 *   <li>Passthrough runtime errors (mapped via {@link ErrorCodes#fromRpcCode})</li>
 * </ul>
 *
 * <p>The {@link GovernanceExceptionHandler} catches this and renders a
 * {@link JsonRpcError} body with HTTP 200.
 *
 * @param errorCode stable error code
 * @param message human-readable message
 * @param requestId JSON-RPC request id (may be null if not yet parsed)
 * @since 0.1.0
 */
public class MethodResultException extends RuntimeException {
    private final ErrorCodes errorCode;
    private final String requestId;

    /**
     * Construct.
     *
     * @param errorCode stable error code constant
     * @param message human-readable message
     * @param requestId JSON-RPC request id (may be null)
     */
    public MethodResultException(ErrorCodes errorCode, String message, String requestId) {
        super(message);
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    /**
     * Construct without a request id (used when the id is not yet available).
     *
     * @param errorCode stable error code constant
     * @param message human-readable message
     */
    public MethodResultException(ErrorCodes errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * Get the stable error code.
     *
     * @return error code
     */
    public ErrorCodes errorCode() {
        return errorCode;
    }

    /**
     * Get the JSON-RPC request id.
     *
     * @return request id, or null if not available
     */
    public String requestId() {
        return requestId;
    }
}
