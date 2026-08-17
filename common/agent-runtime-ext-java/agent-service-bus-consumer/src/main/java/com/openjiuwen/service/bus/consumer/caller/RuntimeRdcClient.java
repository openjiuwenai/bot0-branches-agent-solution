/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Internal HTTP client for the FEAT-015/016 registry discovery API.
 *
 * <p>When RDC is briefly unavailable, returns cached routes within the configured
 * local cache TTL (default one probe period, 5s — Feat-Func-016 L2 §4.5).
 *
 * @since 2026-08-04
 */
public final class RuntimeRdcClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_LOCAL_CACHE_TTL = Duration.ofSeconds(5);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI baseUri;
    private final RuntimeRdcLocalRouteCache cache;

    /**
     * Creates the registry client.
     *
     * @param baseUri registry discovery center base URI
     */
    public RuntimeRdcClient(URI baseUri) {
        this(baseUri, DEFAULT_LOCAL_CACHE_TTL);
    }

    /**
     * Creates the registry client with a local route cache TTL.
     *
     * @param baseUri registry discovery center base URI
     * @param localCacheTtl cache TTL for degraded RDC calls
     */
    public RuntimeRdcClient(URI baseUri, Duration localCacheTtl) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
                new ObjectMapper(), baseUri, localCacheTtl, System::currentTimeMillis);
    }

    RuntimeRdcClient(HttpClient httpClient, ObjectMapper objectMapper, URI baseUri) {
        this(httpClient, objectMapper, baseUri, DEFAULT_LOCAL_CACHE_TTL, System::currentTimeMillis);
    }

    RuntimeRdcClient(HttpClient httpClient, ObjectMapper objectMapper, URI baseUri,
                     Duration localCacheTtl, LongSupplier clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient is required");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper is required");
        this.baseUri = normalizeBaseUri(baseUri);
        this.cache = new RuntimeRdcLocalRouteCache(
                localCacheTtl != null ? localCacheTtl : DEFAULT_LOCAL_CACHE_TTL, clock);
    }

    /**
     * Returns the ordered Runtime candidates registered for an Agent.
     *
     * @param tenantId trusted tenant scope
     * @param agentId target Agent identifier
     * @return registry-ordered candidates
     */
    public List<RouteCandidate> findCandidates(String tenantId, String agentId) {
        URI uri = endpoint("/api/registry/instances/" + encode(tenantId) + "/" + encode(agentId));
        HttpRequest request = HttpRequest.newBuilder(uri).header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT).GET().build();
        try {
            JsonNode response = execute(request, "RDC instance query failed for agentId=" + agentId);
            List<RouteCandidate> candidates = parseCandidates(response);
            cache.putSearch(tenantId, agentId, candidates);
            return candidates;
        } catch (RouteDiscoveryException ex) {
            if (isUnavailable(ex)) {
                return cache.getSearch(tenantId, agentId)
                        .orElseThrow(() -> new RouteDiscoveryException(
                                "RDC instance query unavailable and no cached route for agentId=" + agentId));
            }
            throw ex;
        }
    }

    /**
     * Resolves an opaque route handle to the HTTP endpoint bound to its Runtime instance.
     *
     * @param tenantId trusted tenant scope
     * @param routeHandle opaque route handle returned by discovery
     * @return resolved instance route
     */
    public ResolvedRoute resolve(String tenantId, String routeHandle) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of("tenantId", require(tenantId, "tenantId"),
                    "routeHandle", require(routeHandle, "routeHandle")));
        } catch (IOException failure) {
            throw new RouteDiscoveryException("RDC resolve request cannot be serialized", failure);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint("/api/registry/route-handle/resolve"))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            JsonNode response = execute(request, "RDC route-handle resolve failed");
            ResolvedRoute route = new ResolvedRoute(requiredText(response, "endpointUrl"),
                    optionalText(response, "instanceId"), optionalText(response, "routeKey"),
                    optionalText(response, "contractVersion"));
            cache.putResolve(tenantId, routeHandle, route);
            return route;
        } catch (RouteDiscoveryException ex) {
            if (isUnavailable(ex)) {
                return cache.getResolve(tenantId, routeHandle)
                        .orElseThrow(() -> new RouteDiscoveryException(
                                "RDC resolve unavailable and no cached endpoint for routeHandle="
                                        + routeHandle));
            }
            throw ex;
        }
    }

    private List<RouteCandidate> parseCandidates(JsonNode response) {
        if (!response.isArray()) {
            throw new RouteDiscoveryException("RDC instance query returned a non-array response");
        }
        List<RouteCandidate> candidates = new ArrayList<>(response.size());
        for (JsonNode item : response) {
            candidates.add(new RouteCandidate(requiredText(item, "serviceId"),
                    requiredText(item, "routeHandle")));
        }
        return List.copyOf(candidates);
    }

    private JsonNode execute(HttpRequest request, String failureMessage) {
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 503 || status == 502 || status == 504) {
                throw new RouteDiscoveryException(failureMessage + ": HTTP " + status);
            }
            if (status < 200 || status >= 300) {
                throw new RouteDiscoveryException(failureMessage + ": HTTP " + status);
            }
            return objectMapper.readTree(response.body());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new RouteDiscoveryException(failureMessage, failure);
        } catch (IOException failure) {
            throw new RouteDiscoveryException(failureMessage, failure);
        }
    }

    private static boolean isUnavailable(RouteDiscoveryException ex) {
        if (ex.getCause() instanceof IOException) {
            return true;
        }
        String message = ex.getMessage();
        return message != null && (message.contains("HTTP 503")
                || message.contains("HTTP 502")
                || message.contains("HTTP 504"));
    }

    private URI endpoint(String path) {
        return URI.create(baseUri + path);
    }

    private static URI normalizeBaseUri(URI value) {
        URI uri = Objects.requireNonNull(value, "registryBaseUrl is required").normalize();
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || uri.getHost() == null) {
            throw new IllegalArgumentException("registryBaseUrl must be an absolute HTTP(S) URI");
        }
        String text = uri.toString();
        return URI.create(text.endsWith("/") ? text.substring(0, text.length() - 1) : text);
    }

    private static String encode(String value) {
        return URLEncoder.encode(require(value, "path segment"), StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new RouteDiscoveryException("RDC response is missing " + field);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText();
        return value.isBlank() ? null : value;
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    /**
     * Registry candidate used for Agent Bus logical routing.
     *
     * @param serviceId logical target service identifier
     * @param routeHandle opaque instance route handle
     */
    public record RouteCandidate(String serviceId, String routeHandle) {
        public RouteCandidate {
            require(serviceId, "serviceId");
            require(routeHandle, "routeHandle");
        }
    }

    /**
     * Route-handle resolution used by the SubscribeToTask HTTP data channel.
     *
     * @param endpointUrl target Runtime A2A endpoint
     * @param instanceId resolved Runtime instance identifier
     * @param routeKey resolved route key
     * @param contractVersion resolved contract version
     */
    public record ResolvedRoute(String endpointUrl, String instanceId, String routeKey, String contractVersion) {
        public ResolvedRoute {
            require(endpointUrl, "endpointUrl");
        }
    }

    /**
     * Signals a deterministic registry discovery or response-contract failure.
     */
    public static final class RouteDiscoveryException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        RouteDiscoveryException(String message) {
            super(message);
        }

        RouteDiscoveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
