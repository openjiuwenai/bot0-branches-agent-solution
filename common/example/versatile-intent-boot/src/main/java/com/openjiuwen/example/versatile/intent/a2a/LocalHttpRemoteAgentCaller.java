/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller.EventObserver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

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
 * <p>Builds a minimal non-streaming {@link ServeRequest} from the {@link RemoteCall} fields and POSTs it as JSON.
 * Because this compatibility transport receives only an aggregated REST result, it completes
 * {@link RemoteCallOutcome} without publishing synthetic Task, status, or Artifact events.
 *
 * @since 0.1.0
 */
public class LocalHttpRemoteAgentCaller implements RemoteAgentCaller {
    private static final Logger log = LoggerFactory.getLogger(LocalHttpRemoteAgentCaller.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final String A2A_SUFFIX = "/a2a";
    private static final String QUERY_PATH = "/v1/query";
    private static final String METADATA_KEY_USER_ID = "userId";

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
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver eventObserver) {
        String agentName = call.agentName();
        String a2aUrl = registry.resolveUrl(agentName);
        if (a2aUrl == null || a2aUrl.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "LocalHttpRemoteAgentCaller: no local mapping for agentId=" + agentName, null));
        }
        String queryUrl = a2aUrl.endsWith(A2A_SUFFIX)
                ? a2aUrl.substring(0, a2aUrl.length() - A2A_SUFFIX.length()) + QUERY_PATH
                : a2aUrl + QUERY_PATH;
        ServeRequest forwarded = buildForwardedRequest(call);

        log.info("LocalHttp call agent={} url={} messageLen={}",
                agentName, queryUrl,
                call.message() != null ? call.message().length() : 0);

        CompletableFuture<RemoteCallOutcome> result = new CompletableFuture<>();
        try {
            String body = MAPPER.writeValueAsString(forwarded);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(queryUrl))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .whenComplete((response, ex) -> {
                        if (ex != null) {
                            result.completeExceptionally(new IllegalStateException(
                                    "LocalHttp call to " + queryUrl + " failed", ex));
                            return;
                        }
                        handleResponse(response, queryUrl, agentName, result);
                    });
        } catch (JsonProcessingException | IllegalArgumentException e) {
            result.completeExceptionally(new IllegalStateException(
                    "LocalHttp call to " + queryUrl + " failed", e));
        }
        return result;
    }

    private static ServeRequest buildForwardedRequest(RemoteCall call) {
        ServeRequest forwarded = new ServeRequest();
        forwarded.setConversationId(call.contextId());
        forwarded.setStream(false);
        Object userId = call.metadata() != null ? call.metadata().get(METADATA_KEY_USER_ID) : null;
        if (userId instanceof String uid && !uid.isBlank()) {
            forwarded.setUserId(uid);
        }
        forwarded.setMetadata(call.metadata());
        String messageText = call.message() != null ? call.message() : "";
        forwarded.setMessages(List.of(Map.of("role", "user", "content", messageText)));
        return forwarded;
    }

    private void handleResponse(HttpResponse<String> response, String queryUrl, String agentName,
            CompletableFuture<RemoteCallOutcome> result) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            result.completeExceptionally(new IllegalStateException(
                    "LocalHttp call to " + queryUrl + " failed: HTTP " + response.statusCode()
                            + " body=" + response.body(), null));
            return;
        }
        try {
            Map<?, ?> responseJson = MAPPER.readValue(response.body(), Map.class);
            Object resultObj = responseJson.get("result");
            if (!(resultObj instanceof Map<?, ?> resultMap)) {
                result.completeExceptionally(new IllegalStateException(
                        "LocalHttp response from " + queryUrl + " has no result map", null));
                return;
            }
            String answerText = resultMap.get("text") instanceof String text ? text
                    : (resultMap.get("response_content") instanceof String rc ? rc
                            : resultMap.toString());
            result.complete(new RemoteCallOutcome(null, TaskState.TASK_STATE_COMPLETED,
                    "COMPLETED", answerText, null));
        } catch (JsonProcessingException e) {
            result.completeExceptionally(new IllegalStateException(
                    "LocalHttp call to " + queryUrl + " failed to parse response", e));
        }
    }
}
