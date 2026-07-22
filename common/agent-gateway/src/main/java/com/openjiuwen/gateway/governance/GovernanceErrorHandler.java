/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link GovernanceException} (thrown by any G1–G5 step) to a stable HTTP
 * {@link GatewayError} body (FEAT-011 L2 §3). Centralising the mapping keeps the
 * error shape uniform across all governance / routing failures.
 *
 * <p>{@code traceId} is generated here as a 730 simplification; the G5 slice
 * moves trace-id capture to request entry (from {@code traceparent}) so the same
 * id appears in both this body and the audit log.
 *
 * @since 0.1.0
 */
@RestControllerAdvice
public class GovernanceErrorHandler {
    /**
     * Handle a governance / routing failure.
     *
     * @param ex the failure
     * @return HTTP response with the stable error body
     */
    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<GatewayError> handle(GovernanceException ex) {
        // Use the request-scoped traceId captured at entry (G5); fall back to a
        // generated id only if none was attached.
        String traceId = ex.traceId() != null ? ex.traceId() : UUID.randomUUID().toString();
        GatewayError body = new GatewayError(ex.code(), ex.getMessage(), traceId);
        return ResponseEntity.status(ex.httpStatus()).body(body);
    }
}
