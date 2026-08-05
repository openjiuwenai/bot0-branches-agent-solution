/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests the internal FEAT-015/016 registry client contract.
 *
 * @since 2026-08-04
 */
class RuntimeRdcClientTest {
    private HttpServer server;
    private RuntimeRdcClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        client = new RuntimeRdcClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void returnsRegistryOrderedCandidates() {
        server.createContext("/api/registry/instances/tenant-a/agent-a", exchange -> respond(exchange, 200,
                "[{\"serviceId\":\"runtime-1\",\"routeHandle\":\"route-1\"},"
                        + "{\"serviceId\":\"runtime-2\",\"routeHandle\":\"route-2\"}]"));

        assertThat(client.findCandidates("tenant-a", "agent-a"))
                .containsExactly(new RuntimeRdcClient.RouteCandidate("runtime-1", "route-1"),
                        new RuntimeRdcClient.RouteCandidate("runtime-2", "route-2"));
    }

    @Test
    void resolvesRouteHandleAndSendsTenantScope() {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server.createContext("/api/registry/route-handle/resolve", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"endpointUrl\":\"http://runtime-a:8080/a2a\","
                    + "\"instanceId\":\"instance-a\",\"routeKey\":\"route-key-a\","
                    + "\"contractVersion\":\"1\"}");
        });

        RuntimeRdcClient.ResolvedRoute route = client.resolve("tenant-a", "route-1");

        assertThat(route.endpointUrl()).isEqualTo("http://runtime-a:8080/a2a");
        assertThat(route.instanceId()).isEqualTo("instance-a");
        assertThat(requestBody.get()).contains("\"tenantId\":\"tenant-a\"")
                .contains("\"routeHandle\":\"route-1\"");
    }

    @Test
    void rejectsRegistryFailureAndMalformedResponse() {
        server.createContext("/api/registry/instances/tenant-a/missing", exchange -> respond(exchange, 404, "{}"));
        server.createContext("/api/registry/instances/tenant-a/malformed",
                exchange -> respond(exchange, 200, "[{\"serviceId\":\"runtime-1\"}]"));

        assertThatThrownBy(() -> client.findCandidates("tenant-a", "missing"))
                .isInstanceOf(RuntimeRdcClient.RouteDiscoveryException.class)
                .hasMessageContaining("HTTP 404");
        assertThatThrownBy(() -> client.findCandidates("tenant-a", "malformed"))
                .isInstanceOf(RuntimeRdcClient.RouteDiscoveryException.class)
                .hasMessageContaining("routeHandle");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
