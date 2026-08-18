/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.util.UUID;

/**
 * Maps gateway exceptions to stable HTTP + JSON bodies (FEAT-011 L2 §3 / §8.5.3).
 *
 * <p>Two error surface shapes (A1 unified envelope):
 * <ul>
 *   <li><b>Governance / method-front errors</b> ({@link GovernanceException}):
 *       non-2xx HTTP + flat {@link GatewayError} body (unchanged from 730).</li>
 *   <li><b>Method-result errors</b> ({@link MethodResultException}):
 *       HTTP 200 + {@link JsonRpcError} envelope carrying
 *       {@code error.code} (numeric) + {@code error.data.code} (stable string)
 *       + {@code error.data.retryable}.</li>
 * </ul>
 *
 * <p>Both bodies are written directly to the {@link HttpServletResponse} as
 * {@code application/json}, bypassing Spring content negotiation. A streaming
 * client that sends {@code Accept: text/event-stream} would otherwise trigger a
 * {@code 406} (no text/event-stream converter for the error body), masking the
 * real error with a transport failure.
 *
 * @since 0.1.0
 */
@RestControllerAdvice
public class GovernanceErrorHandler {
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Handle a governance / routing failure by writing the stable flat JSON
     * error body directly, regardless of the client's {@code Accept} header.
     *
     * @param ex the failure
     * @param response servlet response to write into
     * @throws IOException if writing the response fails
     */
    @ExceptionHandler(GovernanceException.class)
    public void handle(GovernanceException ex, HttpServletResponse response) throws IOException {
        String traceId = ex.traceId() != null ? ex.traceId() : UUID.randomUUID().toString();
        GatewayError body = new GatewayError(ex.code(), ex.getMessage(), traceId);
        response.setStatus(ex.httpStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
        response.getWriter().flush();
    }

    /**
     * Handle a method-result error by writing a JSON-RPC {@code error} envelope
     * with HTTP 200 (A1 — method-result errors surface as 200 + JSON-RPC error,
     * not as non-2xx governance errors).
     *
     * @param ex the method-result error
     * @param response servlet response to write into
     * @throws IOException if writing the response fails
     */
    @ExceptionHandler(MethodResultException.class)
    public void handleMethodResult(MethodResultException ex, HttpServletResponse response) throws IOException {
        JsonRpcError body = JsonRpcError.of(ex.requestId(), ex.errorCode(), ex.getMessage());
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
        response.getWriter().flush();
    }
}
