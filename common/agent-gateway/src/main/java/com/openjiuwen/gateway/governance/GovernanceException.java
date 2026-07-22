/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

import org.springframework.http.HttpStatusCode;

/**
 * Thrown by any ingress-governance step (G1–G5) to short-circuit the pipeline
 * with a stable, client-facing error. Carries the HTTP status and a stable
 * {@code code} (e.g. {@code AUTH_MISSING}); {@code traceId} is attached at
 * response-rendering time (obs / G5), not here, so G1 logic stays trace-agnostic.
 *
 * <p>On this exception the gateway MUST NOT query RDC, call runtime, or open SSE
 * (FEAT-011 L2 §1.2, §3 failure rule).
 *
 * @since 0.1.0
 */
public class GovernanceException extends RuntimeException {
    private final HttpStatusCode httpStatus;
    private final String code;
    private String traceId;

    /**
     * Construct.
     *
     * @param httpStatus HTTP status to return (401/403/400/409 ...)
     * @param code stable error code (AUTH_*, TENANT_*, VALIDATION_*, IDEMPOTENCY_*)
     * @param message human-readable message (safe to show callers)
     */
    public GovernanceException(HttpStatusCode httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    /** @return HTTP status to return */
    public HttpStatusCode httpStatus() {
        return httpStatus;
    }

    /** @return stable error code */
    public String code() {
        return code;
    }

    /** @return request trace id (set by the audit wrapper; null until then) */
    public String traceId() {
        return traceId;
    }

    /** Attach the request trace id so the error body and audit share it. */
    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }
}
