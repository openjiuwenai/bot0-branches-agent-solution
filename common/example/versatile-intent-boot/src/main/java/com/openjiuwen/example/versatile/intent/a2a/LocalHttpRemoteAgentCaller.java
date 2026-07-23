/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Local HTTP {@link RemoteAgentCaller} for L2 §5.5.3 方案 B 联调.
 *
 * <p>Calls the target runtime's {@code /v1/query} endpoint directly via HTTP,
 * bypassing the A2A SDK (which has ServiceLoader transport issues in Spring
 * Boot fat jars). The target URL is resolved from {@link A2ARemoteAgentCardRegistry}
 * (populated by {@link LocalMappingCardRegistrar}): the registry entry's
 * JSON-RPC URL ({@code http://localhost:PORT/a2a}) is rewritten to
 * {@code http://localhost:PORT/v1/query}.
 *
 * <p>Uses {@link ForwardedServeRequests#build} to append the upstream
 * {@code response_content} as an assistant message, matching the forwarding
 * semantics of {@link InProcessRemoteAgentCaller} and
 * {@link A2AGatewayRemoteAgentCaller}.
 *
 * <p>The response {@code result} Map is emitted as a single {@code TYPE_CHUNK}
 * with {@code type=answer} so the orchestrator's
 * {@code RemoteCallCaptureObserver} captures the business text and any
 * {@code agent_id} for further forwarding.
 *
 * @since 0.1.0
 */
public class LocalHttpRemoteAgentCaller implements RemoteAgentCaller {
    private static final Logger log = LoggerFactory.getLogger(LocalHttpRemoteAgentCaller.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final String A2A_SUFFIX = "/a2a";
    private static final String QUERY_PATH = "/v1/query";

    private final A2ARemoteAgentCardRegistry registry;
    private final HttpClient httpClient;

    public LocalHttpRemoteAgentCaller(A2ARemoteAgentCardRegistry registry) {
        this.registry = registry;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public void call(RemoteAgentCall call, QueryStreamObserver observer) {
        String a2aUrl = registry.resolveUrl(call.agentId());
        if (a2aUrl == null || a2aUrl.isBlank()) {
            observer.onError(new IllegalStateException(
                    "LocalHttpRemoteAgentCaller: no local mapping for agentId=" + call.agentId()));
            return;
        }
        String queryUrl = a2aUrl.endsWith(A2A_SUFFIX)
                ? a2aUrl.substring(0, a2aUrl.length() - A2A_SUFFIX.length()) + QUERY_PATH
                : a2aUrl + QUERY_PATH;
        ServeRequest forwarded = ForwardedServeRequests.build(call.serveRequest(), call.responseContent());

        log.info("LocalHttp call agent={} url={} appendedMessages={}",
                call.agentId(), queryUrl,
                forwarded.getMessages().size() - call.serveRequest().getMessages().size());

        try {
            String body = MAPPER.writeValueAsString(forwarded);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(queryUrl))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                observer.onError(new IllegalStateException(
                        "LocalHttp call to " + queryUrl + " failed: HTTP " + response.statusCode()
                                + " body=" + response.body()));
                return;
            }
            Map<?, ?> responseJson = MAPPER.readValue(response.body(), Map.class);
            Object resultObj = responseJson.get("result");
            if (!(resultObj instanceof Map<?, ?> resultMap)) {
                observer.onError(new IllegalStateException(
                        "LocalHttp response from " + queryUrl + " has no result map"));
                return;
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "answer");
            envelope.putAll((Map<String, Object>) toObjectType(resultMap));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
            if (!observer.isCancelled()) {
                observer.onComplete();
            }
        } catch (Exception e) {
            observer.onError(new IllegalStateException(
                    "LocalHttp call to " + queryUrl + " failed", e));
        }
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && registry.get(agentId).isPresent();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toObjectType(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
