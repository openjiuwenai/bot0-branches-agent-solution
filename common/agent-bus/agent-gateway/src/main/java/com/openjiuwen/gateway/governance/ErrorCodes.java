/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

/**
 * Stable error-code constants for the A1 unified error envelope (FEAT-011 L2
 * §8.5.3 / cross-feature §5.1).
 *
 * <p>Two error surface shapes coexist:
 * <ul>
 *   <li><b>Governance / method-front errors</b> (G1–G5): non-2xx HTTP + flat
 *       {@link GatewayError} body (unchanged from 730).</li>
 *   <li><b>Method-result errors</b> (UNKNOWN / DIRECT_TRANSPORT_UNKNOWN / TASK_NOT_FOUND /
 *       CONTINUATION_FAILED / STREAM_NOT_AVAILABLE / PAYLOAD_REF_UNAVAILABLE /
 *       passthrough runtime JSON-RPC errors): HTTP 200 + {@link JsonRpcError}
 *       envelope carrying {@code error.code} (numeric) +
 *       {@code error.data.code} (stable string) +
 *       {@code error.data.retryable}.</li>
 * </ul>
 *
 * <p>This enum provides the stable-code constants, numeric JSON-RPC codes,
 * retryable semantics, and a reverse lookup from runtime JSON-RPC codes.
 *
 * @since 0.1.0
 */
public enum ErrorCodes {
    PROJECTION_TIMEOUT_UNKNOWN(-32050, "PROJECTION_TIMEOUT_UNKNOWN", true),
    // DF-003: DIRECT transport failure before taskId (do NOT retry original create)
    DIRECT_TRANSPORT_UNKNOWN(-32053, "DIRECT_TRANSPORT_UNKNOWN", false),
    CONTINUATION_FAILED(-32051, "CONTINUATION_FAILED", false),
    STREAM_NOT_AVAILABLE(-32052, "STREAM_NOT_AVAILABLE", false),
    PAYLOAD_REF_UNAVAILABLE(-32054, "PAYLOAD_REF_UNAVAILABLE", true),
    TASK_NOT_FOUND(-32001, "TASK_NOT_FOUND", false),
    UNSUPPORTED_OPERATION(-32004, "UNSUPPORTED_OPERATION", false),
    VALIDATION_FAILED(-32602, "VALIDATION_FAILED", false),
    VALIDATION_METHOD(-32601, "VALIDATION_METHOD", false),
    INTERNAL_ERROR(-32603, "INTERNAL_ERROR", false);

    private final int numericCode;
    private final String stableCode;
    private final boolean retryable;

    ErrorCodes(int numericCode, String stableCode, boolean retryable) {
        this.numericCode = numericCode;
        this.stableCode = stableCode;
        this.retryable = retryable;
    }

    /**
     * Get the numeric JSON-RPC error code.
     *
     * @return numeric code (e.g. -32050)
     */
    public int numericCode() {
        return numericCode;
    }

    /**
     * Get the stable string code placed in {@code error.data.code}.
     *
     * @return stable string code (e.g. "PROJECTION_TIMEOUT_UNKNOWN")
     */
    public String stableCode() {
        return stableCode;
    }

    /**
     * Whether the client should retry (same-key for PROJECTION_TIMEOUT_UNKNOWN,
     * generic retry for PAYLOAD_REF_UNAVAILABLE).
     *
     * @return true if retryable
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * Reverse-lookup from a runtime JSON-RPC numeric code to the gateway stable
     * code. Used when re-wrapping passthrough runtime errors (R12 — runtime
     * error carries only {@code code}+{@code message}, no {@code data} field;
     * gateway enriches with {@code data.code}/{@code retryable}).
     *
     * @param rpcCode runtime JSON-RPC numeric code
     * @return matching ErrorCodes, or null if unknown
     */
    public static ErrorCodes fromRpcCode(int rpcCode) {
        return switch (rpcCode) {
            case -32001 -> TASK_NOT_FOUND;
            case -32004 -> UNSUPPORTED_OPERATION;
            case -32602 -> VALIDATION_FAILED;
            case -32601 -> VALIDATION_METHOD;
            case -32603 -> INTERNAL_ERROR;
            default -> null;
        };
    }

    /**
     * Check whether a stable string code is retryable. Unrecognised codes are
     * fail-closed (not retryable).
     *
     * @param stableCode stable string code
     * @return true if the code is recognised and retryable
     */
    public static boolean isRetryable(String stableCode) {
        for (ErrorCodes ec : values()) {
            if (ec.stableCode.equals(stableCode)) {
                return ec.retryable;
            }
        }
        return false;
    }
}
