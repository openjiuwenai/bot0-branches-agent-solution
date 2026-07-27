/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.direct;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openjiuwen.gateway.governance.GovernanceException;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Tests {@link HttpAgentRuntimeClient} against a {@link MockWebServer}: asserts
 * the body is forwarded to {@code POST <endpoint>/a2a} and returned as-is, and a
 * transport failure maps to FORWARD_FAILED.
 */
class HttpAgentRuntimeClientTest {
    private MockWebServer mockRuntime;
    private HttpAgentRuntimeClient client;
    private String endpoint;

    @BeforeEach
    void setUp() throws IOException {
        endpoint = openMockRuntime();
        client = new HttpAgentRuntimeClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        closeMockRuntime();
    }

    /**
     * Starts an in-process runtime stand-in and returns its base URL (no trailing slash).
     *
     * @return base URL of the stand-in
     * @throws IOException when the stand-in fails to bind
     */
    private String openMockRuntime() throws IOException {
        mockRuntime = new MockWebServer();
        mockRuntime.start();
        String root = mockRuntime.url("/").toString();
        return root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
    }

    /**
     * Stops the in-process runtime stand-in if it was started.
     *
     * @throws IOException when shutdown fails
     */
    private void closeMockRuntime() throws IOException {
        if (mockRuntime != null) {
            mockRuntime.shutdown();
            mockRuntime = null;
        }
    }

    @Test
    void forwardsBodyToA2aAndReturnsResponse() throws InterruptedException {
        mockRuntime.enqueue(new MockResponse()
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":\"req-1\",\"result\":{\"id\":\"task-1\"}}")
                .addHeader("Content-Type", "application/json"));
        String resp = client.invokeSync(endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"SendMessage\"}");
        assertThat(resp).contains("\"id\":\"task-1\"");
        RecordedRequest req = mockRuntime.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).endsWith("/a2a");
        assertThat(req.getBody().readUtf8()).contains("\"method\":\"SendMessage\"");
    }

    @Test
    void transportFailureReturnsForwardFailed() {
        mockRuntime.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        Throwable thrown = catchThrowable(() -> client.invokeSync(endpoint, "{}"));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        GovernanceException ge = asGovernanceException(thrown);
        assertThat(ge.code()).isEqualTo("FORWARD_FAILED");
        // topology must not leak in the gateway-controlled error message
        assertThat(ge.getMessage()).doesNotContain(endpoint);
    }

    private static GovernanceException asGovernanceException(Throwable thrown) {
        if (thrown instanceof GovernanceException ge) {
            return ge;
        }
        throw new AssertionError("expected GovernanceException but got: " + thrown);
    }
}
