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
import java.util.List;

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

    @Test
    void invokeSync503ReturnsForwardFailedNot200() {
        // issue 139: runtime returns 503 (service unavailable) — must surface as FORWARD_FAILED (502),
        // not masked as HTTP 200 + raw body.
        mockRuntime.enqueue(new MockResponse().setResponseCode(503).setBody("runtime unavailable"));
        Throwable thrown = catchThrowable(() -> client.invokeSync(endpoint, "{}"));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        GovernanceException ge = asGovernanceException(thrown);
        assertThat(ge.code()).isEqualTo("FORWARD_FAILED");
        assertThat(ge.getMessage()).contains("503");
    }

    @Test
    void invokeSync200WithJsonRpcErrorPassesThrough() {
        // 200 + JSON-RPC error (e.g., -32004 terminal task) is a method error, NOT a service failure.
        // Must be transparently passed through as-is (the fix for >=400 must NOT touch 200).
        String jsonRpcError = "{\"jsonrpc\":\"2.0\",\"id\":\"2\",\"error\":{\"code\":-32004,"
                + "\"message\":\"Cannot subscribe to task - task is in terminal state: TASK_STATE_COMPLETED\"}}";
        mockRuntime.enqueue(new MockResponse()
                .setBody(jsonRpcError)
                .addHeader("Content-Type", "application/json"));
        String resp = client.invokeSync(endpoint, "{\"jsonrpc\":\"2.0\",\"method\":\"SubscribeToTask\"}");
        assertThat(resp).isEqualTo(jsonRpcError);
    }

    @Test
    void invokeSync404ReturnsForwardFailed() {
        // 404 from runtime (non-2xx) → FORWARD_FAILED, not 200 + body.
        mockRuntime.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        Throwable thrown = catchThrowable(() -> client.invokeSync(endpoint, "{}"));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        GovernanceException ge = asGovernanceException(thrown);
        assertThat(ge.code()).isEqualTo("FORWARD_FAILED");
        assertThat(ge.getMessage()).contains("404");
    }

    @Test
    void openStreamByRefReturnsDataFrames() throws InterruptedException {
        mockRuntime.enqueue(new MockResponse()
                .setBody("event: jsonrpc\ndata: {\"id\":\"t1\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
        List<String> frames = client.openStreamByRef(endpoint, "sr-1", "t1", "tenant-a").toList();
        assertThat(frames).containsExactly("{\"id\":\"t1\"}");
        RecordedRequest req = mockRuntime.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).endsWith("/a2a");
        assertThat(req.getHeader("Accept")).isEqualTo("text/event-stream");
        assertThat(req.getHeader("X-OpenJiuwen-Stream-Ref")).isEqualTo("sr-1");
        assertThat(req.getBody().readUtf8()).contains("SubscribeToTask").contains("\"id\":\"t1\"");
    }

    @Test
    void openStreamByRefRejects4xx() {
        // A runtime that rejects SubscribeToTask (e.g. TaskNotFoundError → 4xx) must surface as a
        // FORWARD_FAILED governance error carrying the status — not be silently swallowed as an
        // empty stream (which masks the real cause and yields an empty SSE to the client).
        mockRuntime.enqueue(new MockResponse().setResponseCode(404).setBody("not found"));
        Throwable thrown = catchThrowable(() -> client.openStreamByRef(endpoint, "sr-1", "t1", "tenant-a"));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        GovernanceException ge = asGovernanceException(thrown);
        assertThat(ge.code()).isEqualTo("FORWARD_FAILED");
        assertThat(ge.getMessage()).contains("404");
    }

    @Test
    void openStreamReturnsDataFrames() {
        mockRuntime.enqueue(new MockResponse()
                .setBody("data: {\"id\":\"t2\"}\n\n")
                .setHeader("Content-Type", "text/event-stream"));
        List<String> frames = client.openStream(endpoint, "{\"jsonrpc\":\"2.0\"}").toList();
        assertThat(frames).containsExactly("{\"id\":\"t2\"}");
    }

    @Test
    void openStreamRejects4xx() {
        // Same robustness for the DIRECT streaming path.
        mockRuntime.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
        Throwable thrown = catchThrowable(() -> client.openStream(endpoint, "{\"jsonrpc\":\"2.0\"}"));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        GovernanceException ge = asGovernanceException(thrown);
        assertThat(ge.code()).isEqualTo("FORWARD_FAILED");
        assertThat(ge.getMessage()).contains("500");
    }

    private static GovernanceException asGovernanceException(Throwable thrown) {
        if (thrown instanceof GovernanceException ge) {
            return ge;
        }
        throw new AssertionError("expected GovernanceException but got: " + thrown);
    }
}
