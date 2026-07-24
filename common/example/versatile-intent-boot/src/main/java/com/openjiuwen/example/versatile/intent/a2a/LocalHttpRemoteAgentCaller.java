/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

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
 * <p>Builds a minimal {@link ServeRequest} from the {@link RemoteCall} fields
 * and POSTs it as JSON. The response {@code result} Map is emitted as a
 * single {@code TYPE_CHUNK} with {@code type=answer} so the orchestrator's
 * batch coordinator captures the business text for the caller.
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
    public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
            QueryStreamObserver streamObserver, Consumer<String> remoteTaskIdObserver) {
        String agentName = call.agentName();
        String a2aUrl = registry.resolveUrl(agentName);
        if (a2aUrl == null || a2aUrl.isBlank()) {
            return CompletableFuture.failedFuture(new RemoteAgentException(
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
                            result.completeExceptionally(new RemoteAgentException(
                                    "LocalHttp call to " + queryUrl + " failed", ex));
                            return;
                        }
                        handleResponse(response, queryUrl, agentName, result, streamObserver);
                    });
        } catch (Exception e) {
            result.completeExceptionally(new RemoteAgentException(
                    "LocalHttp call to " + queryUrl + " failed", e));
        }
        return result;
    }

    @Override
    public boolean supported(String agentName) {
        return agentName != null && registry.get(agentName).isPresent();
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
            CompletableFuture<RemoteCallOutcome> result, QueryStreamObserver observer) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            result.completeExceptionally(new RemoteAgentException(
                    "LocalHttp call to " + queryUrl + " failed: HTTP " + response.statusCode()
                            + " body=" + response.body(), null));
            return;
        }
        try {
            Map<?, ?> responseJson = MAPPER.readValue(response.body(), Map.class);
            Object resultObj = responseJson.get("result");
            if (!(resultObj instanceof Map<?, ?> resultMap)) {
                result.completeExceptionally(new RemoteAgentException(
                        "LocalHttp response from " + queryUrl + " has no result map", null));
                return;
            }
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", "answer");
            envelope.putAll(toObjectType(resultMap));
            if (observer != null) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
            }
            String answerText = resultMap.get("text") instanceof String text ? text
                    : (resultMap.get("response_content") instanceof String rc ? rc
                            : resultMap.toString());
            result.complete(new RemoteCallOutcome(null, TaskState.TASK_STATE_COMPLETED,
                    "COMPLETED", answerText, null));
        } catch (Exception e) {
            result.completeExceptionally(new RemoteAgentException(
                    "LocalHttp call to " + queryUrl + " failed to parse response", e));
        }
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
