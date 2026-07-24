/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 730 default {@link RdcRouteClient}: calls the RDC HTTP API
 * (FEAT-016 §6.1). Uses the JDK built-in {@link HttpClient} (no extra dependency)
 * and is configured by {@code gateway.rdc.base-url}. The gateway is NOT
 * co-process-coupled to RDC (decision D2) — this is just an HTTP client.
 *
 * <ul>
 *   <li>{@code GET {base}/api/registry/instances/{tenantId}/{agentId}} → candidates</li>
 *   <li>{@code POST {base}/api/registry/route-handle/resolve} → endpoint</li>
 * </ul>
 *
 * @since 0.1.0
 */
@Component
public class HttpRdcRouteClient implements RdcRouteClient {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    /**
     * Construct.
     *
     * @param baseUrl RDC base URL (e.g. http://localhost:8092)
     */
    public HttpRdcRouteClient(@Value("${gateway.rdc.base-url:http://localhost:8092}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public List<AgentCardRoute> searchInstancesByAgentId(String tenantId, String agentId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/registry/instances/" + enc(tenantId) + "/" + enc(agentId)))
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .GET().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                // RDC error / unavailable -> no usable candidates (S5 handles empty).
                return List.of();
            }
            JsonNode root = mapper.readTree(resp.body());
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<AgentCardRoute> out = new ArrayList<>(root.size());
            for (JsonNode node : root) {
                String handle = node.path("routeHandle").asText(null);
                if (handle != null && !handle.isBlank()) {
                    out.add(new AgentCardRoute(handle));
                }
            }
            return out;
        } catch (IOException | InterruptedException ex) {
            // G.CON.10 forbids Thread.interrupt(); wrap as route resolution failure.
            throw new RouteResolutionException("RDC search failed for " + agentId, ex);
        }
    }

    @Override
    public ResolvedRoute resolveRouteHandle(String routeHandle, String tenantId) {
        String body;
        try {
            body = mapper.writeValueAsString(Map.of("routeHandle", routeHandle, "tenantId", tenantId));
        } catch (JsonProcessingException ex) {
            throw new RouteResolutionException("Cannot serialize resolve request", ex);
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/registry/route-handle/resolve"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 400) {
                throw new RouteResolutionException("RDC resolve failed: HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            String endpoint = root.path("endpointUrl").asText(null);
            if (endpoint == null || endpoint.isBlank()) {
                throw new RouteResolutionException("RDC resolve returned no endpointUrl");
            }
            return new ResolvedRoute(endpoint);
        } catch (RouteResolutionException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            // G.CON.10 forbids Thread.interrupt(); wrap as route resolution failure.
            throw new RouteResolutionException("RDC resolve failed", ex);
        }
    }

    private static String enc(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
