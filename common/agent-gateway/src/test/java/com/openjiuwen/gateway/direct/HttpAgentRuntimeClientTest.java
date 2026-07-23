/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openjiuwen.gateway.governance.GovernanceException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

/**
 * Tests {@link HttpAgentRuntimeClient} against a {@link MockWebServer}: asserts
 * the body is forwarded to {@code POST <endpoint>/a2a} and returned as-is, and a
 * transport failure maps to FORWARD_FAILED.
 */
class HttpAgentRuntimeClientTest {
    private MockWebServer server;
    private HttpAgentRuntimeClient client;
    private String endpoint;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("").toString();
        endpoint = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        client = new HttpAgentRuntimeClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void forwardsBodyToA2aAndReturnsResponse() throws InterruptedException {
        server.enqueue(new MockResponse().setBody("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"id\":\"task-1\"}}")
                .addHeader("Content-Type", "application/json"));
        String resp = client.invokeSync(endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"SendMessage\"}");
        assertThat(resp).contains("\"id\":\"task-1\"");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).endsWith("/a2a");
        assertThat(req.getBody().readUtf8()).contains("\"method\":\"SendMessage\"");
    }

    @Test
    void transportFailureReturnsForwardFailed() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        Throwable thrown = catchThrowable(() -> client.invokeSync(endpoint, "{}"));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        GovernanceException ge = (GovernanceException) thrown;
        assertThat(ge.code()).isEqualTo("FORWARD_FAILED");
        // topology must not leak in the gateway-controlled error message
        assertThat(ge.getMessage()).doesNotContain(endpoint);
    }
}
