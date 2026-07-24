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
    private MockWebServer server;
    private HttpRdcRouteClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String base = server.url("").toString();
        client = new HttpRdcRouteClient(base.endsWith("/") ? base.substring(0, base.length() - 1) : base);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void searchReturnsCandidatesFromRdcArray() throws InterruptedException {
        server.enqueue(new MockResponse()
                .setBody("[{\"routeHandle\":\"h1\"},{\"routeHandle\":\"h2\"}]")
                .addHeader("Content-Type", "application/json"));
        List<AgentCardRoute> result = client.searchInstancesByAgentId("tenant-1", "agent-9");
        assertThat(result).extracting(AgentCardRoute::routeHandle).containsExactly("h1", "h2");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("GET");
        assertThat(req.getPath()).endsWith("/api/registry/instances/tenant-1/agent-9");
    }

    @Test
    void searchEmptyArrayReturnsEmpty() {
        server.enqueue(new MockResponse().setBody("[]").addHeader("Content-Type", "application/json"));
        assertThat(client.searchInstancesByAgentId("t", "a")).isEmpty();
    }

    @Test
    void resolveReturnsEndpointAndPostsHandleAndTenant() throws Exception {
        server.enqueue(new MockResponse()
                .setBody("{\"endpointUrl\":\"http://runtime-1:8000\"}")
                .addHeader("Content-Type", "application/json"));
        ResolvedRoute resolved = client.resolveRouteHandle("v2:abc", "tenant-1");
        assertThat(resolved.endpointUrl()).isEqualTo("http://runtime-1:8000");
        RecordedRequest req = server.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).endsWith("/api/registry/route-handle/resolve");
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"routeHandle\":\"v2:abc\"").contains("\"tenantId\":\"tenant-1\"");
    }

    @Test
    void resolveErrorThrowsRouteResolutionException() {
        server.enqueue(new MockResponse().setResponseCode(404));
        Throwable thrown = catchThrowable(() -> client.resolveRouteHandle("v2:gone", "t"));
        assertThat(thrown).isInstanceOf(RouteResolutionException.class);
    }

    @Test
    void resolveMissingEndpointThrows() {
        server.enqueue(new MockResponse().setBody("{\"endpointUrl\":\"\"}")
                .addHeader("Content-Type", "application/json"));
        Throwable thrown = catchThrowable(() -> client.resolveRouteHandle("v2:x", "t"));
        assertThat(thrown).isInstanceOf(RouteResolutionException.class);
    }
}
