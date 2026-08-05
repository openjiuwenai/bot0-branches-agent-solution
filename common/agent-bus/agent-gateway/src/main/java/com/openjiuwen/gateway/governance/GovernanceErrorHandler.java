/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.UUID;

/**
 * Maps {@link GovernanceException} (thrown by any G1–G5 step) to a stable HTTP
 * {@link GatewayError} body (FEAT-011 L2 §3). Centralising the mapping keeps the
 * error shape uniform across all governance / routing failures.
 *
 * <p>The body is written directly to the {@link HttpServletResponse} as
 * {@code application/json}, bypassing Spring content negotiation. A streaming
 * client that sends {@code Accept: text/event-stream} would otherwise trigger a
 * {@code 406 HttpMediaTypeNotAcceptableException} (no text/event-stream converter
 * for the error body), masking the real governance error with a transport failure.
 *
 * <p>{@code traceId} is generated here as a 730 simplification; the G5 slice
 * moves trace-id capture to request entry (from {@code traceparent}) so the same
 * id appears in both this body and the audit log.
 *
 * @since 0.1.0
 */
@RestControllerAdvice
public class GovernanceErrorHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Handle a governance / routing failure by writing the stable JSON error body
     * directly, regardless of the client's {@code Accept} header.
     *
     * @param ex the failure
     * @param response servlet response to write into
     * @throws IOException if writing the response fails
     */
    @ExceptionHandler(GovernanceException.class)
    public void handle(GovernanceException ex, HttpServletResponse response) throws IOException {
        // Use the request-scoped traceId captured at entry (G5); fall back to a
        // generated id only if none was attached.
        String traceId = ex.traceId() != null ? ex.traceId() : UUID.randomUUID().toString();
        GatewayError body = new GatewayError(ex.code(), ex.getMessage(), traceId);
        response.setStatus(ex.httpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
