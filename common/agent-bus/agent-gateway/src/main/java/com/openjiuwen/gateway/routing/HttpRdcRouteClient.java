/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 730 default {@link RdcRouteClient}: calls the RDC HTTP API
 * (FEAT-016 §6.1). Uses the JDK built-in {@link HttpClient} (no extra dependency)
 * and is configured by {@code gateway.rdc.base-url}. The gateway is NOT
 * co-process-coupled to RDC (decision D2) — this is just an HTTP client.
 *
 * <p>When RDC is briefly unavailable, returns a cached route within
 * {@code gateway.rdc.local-cache.ttl-ms} (default one probe period, 5s).
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
    private final LocalRdcRouteCache cache;

    /**
     * Construct.
     *
     * @param baseUrl RDC base URL (e.g. http://localhost:8092)
     * @param cacheTtlMs local route cache TTL in milliseconds (default 5000)
     */
    @Autowired
    public HttpRdcRouteClient(
            @Value("${gateway.rdc.base-url:http://localhost:8092}") String baseUrl,
            @Value("${gateway.rdc.local-cache.ttl-ms:5000}") long cacheTtlMs) {
        this(baseUrl, cacheTtlMs, System::currentTimeMillis);
    }

    HttpRdcRouteClient(String baseUrl, long cacheTtlMs, LongSupplier clock) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.cache = new LocalRdcRouteCache(Duration.ofMillis(cacheTtlMs), clock);
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
            if (resp.statusCode() == 503 || resp.statusCode() == 502 || resp.statusCode() == 504) {
                return cache.getSearch(tenantId, agentId).orElse(List.of());
            }
            if (resp.statusCode() >= 400) {
                return List.of();
            }
            List<AgentCardRoute> parsed = parseSearchBody(resp.body());
            cache.putSearch(tenantId, agentId, parsed);
            return parsed;
        } catch (IOException | InterruptedException ex) {
            Optional<List<AgentCardRoute>> cached = cache.getSearch(tenantId, agentId);
            if (cached.isPresent()) {
                return cached.get();
            }
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
            if (resp.statusCode() == 503 || resp.statusCode() == 502 || resp.statusCode() == 504) {
                return cache.getResolve(tenantId, routeHandle)
                        .map(ResolvedRoute::new)
                        .orElseThrow(() -> new RouteResolutionException(
                                "RDC resolve unavailable and no cached endpoint for handle"));
            }
            if (resp.statusCode() >= 400) {
                throw new RouteResolutionException("RDC resolve failed: HTTP " + resp.statusCode());
            }
            JsonNode root = mapper.readTree(resp.body());
            String endpoint = root.path("endpointUrl").asText(null);
            if (endpoint == null || endpoint.isBlank()) {
                throw new RouteResolutionException("RDC resolve returned no endpointUrl");
            }
            cache.putResolve(tenantId, routeHandle, endpoint);
            return new ResolvedRoute(endpoint);
        } catch (RouteResolutionException ex) {
            throw ex;
        } catch (IOException | InterruptedException ex) {
            Optional<String> cached = cache.getResolve(tenantId, routeHandle);
            if (cached.isPresent()) {
                return new ResolvedRoute(cached.get());
            }
            throw new RouteResolutionException("RDC resolve failed", ex);
        }
    }

    private List<AgentCardRoute> parseSearchBody(String responseBody) throws IOException {
        JsonNode root = mapper.readTree(responseBody);
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<AgentCardRoute> out = new ArrayList<>(root.size());
        for (JsonNode node : root) {
            String handle = node.path("routeHandle").asText(null);
            if (handle != null && !handle.isBlank()) {
                String serviceId = node.path("serviceId").asText(null);
                out.add(new AgentCardRoute(handle, serviceId));
            }
        }
        return out;
    }

    private static String enc(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
