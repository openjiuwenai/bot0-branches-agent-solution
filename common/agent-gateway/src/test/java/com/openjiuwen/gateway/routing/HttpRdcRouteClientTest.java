/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

/**
 * Tests {@link HttpRdcRouteClient} against a {@link MockWebServer} (FEAT-016
 * HTTP contract). Asserts both the returned values and the wire requests.
 */
class HttpRdcRouteClientTest {
    private MockWebServer mockRdc;
    private HttpRdcRouteClient client;

    @BeforeEach
    void setUp() throws IOException {
        client = openMockRdcClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        closeMockRdc();
    }

    /**
     * Starts an in-process RDC stand-in and returns a client aimed at it.
     *
     * @return client whose base URL matches the stand-in
     * @throws IOException when the stand-in fails to bind
     */
    private HttpRdcRouteClient openMockRdcClient() throws IOException {
        mockRdc = new MockWebServer();
        mockRdc.start();
        String root = mockRdc.url("/").toString();
        String baseUrl = root.endsWith("/") ? root.substring(0, root.length() - 1) : root;
        return new HttpRdcRouteClient(baseUrl);
    }

    /**
     * Stops the in-process RDC stand-in if it was started.
     *
     * @throws IOException when shutdown fails
     */
    private void closeMockRdc() throws IOException {
        if (mockRdc != null) {
            mockRdc.shutdown();
            mockRdc = null;
        }
    }

    @Test
    void searchReturnsCandidatesFromRdcArray() throws InterruptedException {
        mockRdc.enqueue(new MockResponse()
                .setBody("[{\"routeHandle\":\"h1\"},{\"routeHandle\":\"h2\"}]")
                .addHeader("Content-Type", "application/json"));
        List<AgentCardRoute> result = client.searchInstancesByAgentId("tenant-1", "agent-9");
        assertThat(result).extracting(AgentCardRoute::routeHandle).containsExactly("h1", "h2");
        RecordedRequest req = mockRdc.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).endsWith("/api/registry/instances/tenant-1/agent-9");
    }

    @Test
    void searchEmptyArrayReturnsEmpty() {
        mockRdc.enqueue(new MockResponse().setBody("[]").addHeader("Content-Type", "application/json"));
        assertThat(client.searchInstancesByAgentId("t", "a")).isEmpty();
    }

    @Test
    void resolveReturnsEndpointAndPostsHandleAndTenant() throws Exception {
        mockRdc.enqueue(new MockResponse()
                .setBody("{\"endpointUrl\":\"http://runtime-1:8000\"}")
                .addHeader("Content-Type", "application/json"));
        ResolvedRoute resolved = client.resolveRouteHandle("v2:abc", "tenant-1");
        assertThat(resolved.endpointUrl()).isEqualTo("http://runtime-1:8000");
        RecordedRequest req = mockRdc.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).endsWith("/api/registry/route-handle/resolve");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"routeHandle\":\"v2:abc\"").contains("\"tenantId\":\"tenant-1\"");
    }

    @Test
    void resolveErrorThrowsRouteResolutionException() {
        mockRdc.enqueue(new MockResponse().setResponseCode(404));
        Throwable thrown = catchThrowable(() -> client.resolveRouteHandle("v2:gone", "t"));
        assertThat(thrown).isInstanceOf(RouteResolutionException.class);
    }

    @Test
    void resolveMissingEndpointThrows() {
        mockRdc.enqueue(new MockResponse().setBody("{\"endpointUrl\":\"\"}")
                .addHeader("Content-Type", "application/json"));
        Throwable thrown = catchThrowable(() -> client.resolveRouteHandle("v2:x", "t"));
        assertThat(thrown).isInstanceOf(RouteResolutionException.class);
    }
}
