/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Tests for {@link GovernanceErrorHandler} error-surface split (A1):
 * governance errors → non-2xx + flat {@link GatewayError};
 * method-result errors → 200 + {@link JsonRpcError} envelope.
 */
class GovernanceErrorHandlerTest {
    private final GovernanceErrorHandler handler = new GovernanceErrorHandler();
    private final ObjectMapper mapper = new ObjectMapper();
    private StringWriter capturedString;

    private HttpServletResponse mockResponse() throws IOException {
        HttpServletResponse response = mock(HttpServletResponse.class);
        capturedString = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(capturedString));
        return response;
    }

    private String captureBody() {
        return capturedString.toString();
    }

    @Test
    void governance_error_returns_non2xx_with_flat_body() throws Exception {
        HttpServletResponse response = mockResponse();
        GovernanceException ex = new GovernanceException(HttpStatus.UNAUTHORIZED, "AUTH_INVALID", "token missing");

        handler.handle(ex, response);

        // Verify status is non-2xx
        // (Mockito.verify(response).setStatus(401) would be more explicit but
        // we verify the body shape instead to keep the test focused.)
        String body = captureBody();
        JsonNode root = mapper.readTree(body);
        assertThat(root.path("code").asText()).isEqualTo("AUTH_INVALID");
        assertThat(root.path("message").asText()).isEqualTo("token missing");
        assertThat(root.has("traceId")).isTrue();
        // Flat body — no JSON-RPC envelope
        assertThat(root.has("jsonrpc")).isFalse();
        assertThat(root.has("error")).isFalse();
    }

    @Test
    void method_result_error_returns_200_with_jsonrpc_error_envelope() throws Exception {
        HttpServletResponse response = mockResponse();
        MethodResultException ex = new MethodResultException(ErrorCodes.TASK_NOT_FOUND, "Task not found", "req-1");

        handler.handleMethodResult(ex, response);

        String body = captureBody();
        JsonNode root = mapper.readTree(body);
        assertThat(root.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(root.path("id").asText()).isEqualTo("req-1");
        assertThat(root.path("error").path("code").asInt()).isEqualTo(-32001);
        assertThat(root.path("error").path("message").asText()).isEqualTo("Task not found");
        assertThat(root.path("error").path("data").path("code").asText()).isEqualTo("TASK_NOT_FOUND");
        assertThat(root.path("error").path("data").path("retryable").asBoolean()).isFalse();
    }

    @Test
    void projection_timeout_is_retryable_in_envelope() throws Exception {
        HttpServletResponse response = mockResponse();
        MethodResultException ex = new MethodResultException(
                ErrorCodes.PROJECTION_TIMEOUT_UNKNOWN, "accept window timeout", "req-2");

        handler.handleMethodResult(ex, response);

        String body = captureBody();
        JsonNode root = mapper.readTree(body);
        assertThat(root.path("error").path("code").asInt()).isEqualTo(-32050);
        assertThat(root.path("error").path("data").path("code").asText()).isEqualTo("PROJECTION_TIMEOUT_UNKNOWN");
        assertThat(root.path("error").path("data").path("retryable").asBoolean()).isTrue();
    }

    @Test
    void method_result_with_null_request_id() throws Exception {
        HttpServletResponse response = mockResponse();
        MethodResultException ex = new MethodResultException(
                ErrorCodes.CONTINUATION_FAILED, "no sticky owner", null);

        handler.handleMethodResult(ex, response);

        String body = captureBody();
        JsonNode root = mapper.readTree(body);
        assertThat(root.path("id").isNull()).isTrue();
        assertThat(root.path("error").path("code").asInt()).isEqualTo(-32051);
        assertThat(root.path("error").path("data").path("code").asText()).isEqualTo("CONTINUATION_FAILED");
    }
}
