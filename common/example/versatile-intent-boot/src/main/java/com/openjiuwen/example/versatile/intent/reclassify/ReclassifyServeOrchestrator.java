/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.example.versatile.intent.routecache.RouteCache;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decorator around {@link ServeOrchestrator} that retries L1 intent
 * recognition with augmented context when L2 returns an answer envelope
 * whose {@code intent_id} matches the configured ambiguous id.
 *
 * <p>Signal path: L2 adapter produces a {@code TYPE_CHUNK} answer envelope
 * carrying {@code intent_id}. The envelope traverses HTTP / gateway / batch
 * coordinator as a normal 200 response and surfaces as the
 * {@code QueryResponse.result.content} (a JSON string) at this decorator.
 * The decorator parses the content via
 * {@link AmbiguousPayloadParser#fromQueryResponse} and, on match, rebuilds
 * the request with the L2 {@code response_content} appended as an assistant
 * message, then re-invokes the wrapped orchestrator.
 *
 * <p>Streaming path: each attempt is issued with a fresh
 * {@link ReclassifyObserver}; the observer inspects {@code TYPE_CHUNK}
 * envelopes via {@link AmbiguousPayloadParser#fromChunkData} and, on match,
 * swallows the chunk so the runtime does not surface it to the client.
 *
 * <p>The counter is a method-local variable — there is no cross-request
 * state. {@code max-reclassify=0} disables retry and produces
 * {@code VERSATILE_INTENT_RECLASSIFY_LIMIT} immediately on the first
 * ambiguous signal.
 *
 * <p>When an ambiguous payload is detected and a retry is about to be
 * issued, the L1 {@link RouteCache} for the active conversation is
 * invalidated. Without this, the retry would skip L1 via the cache and
 * reuse the very route that produced the ambiguous response. The cache
 * is injected as an {@link Optional} so the decorator keeps working when
 * route-cache support is disabled at runtime.
 *
 * @since 2026-07-24
 */
public class ReclassifyServeOrchestrator implements ServeOrchestrator {
    private static final String LIMIT_CODE = "VERSATILE_INTENT_RECLASSIFY_LIMIT";

    private final ServeOrchestrator wrapped;
    private final ReclassifyProperties properties;
    private final Optional<RouteCache> routeCache;

    /**
     * Primary constructor — accepts an optional {@link RouteCache} that is
     * invalidated before each reclassify retry.
     *
     * @param wrapped the underlying orchestrator to delegate to
     * @param properties reclassify configuration
     * @param routeCache optional L1 route cache; cleared on retry
     */
    public ReclassifyServeOrchestrator(ServeOrchestrator wrapped, ReclassifyProperties properties,
                                       Optional<RouteCache> routeCache) {
        this.wrapped = Objects.requireNonNull(wrapped, "wrapped");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.routeCache = Objects.requireNonNullElse(routeCache, Optional.empty());
    }

    /**
     * Backwards-compatible constructor for callers that do not wire a
     * route cache. Equivalent to passing {@link Optional#empty()}.
     *
     * @param wrapped the underlying orchestrator to delegate to
     * @param properties reclassify configuration
     */
    public ReclassifyServeOrchestrator(ServeOrchestrator wrapped, ReclassifyProperties properties) {
        this(wrapped, properties, Optional.empty());
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        if (!properties.isEnabled()) {
            return wrapped.query(request);
        }
        int reclassifyCount = 0;
        ServeRequest current = request;
        while (true) {
            QueryResponse response = wrapped.query(current);
            Optional<AmbiguousPayload> ambiguous = AmbiguousPayloadParser.fromQueryResponse(
                    response, properties.getAmbiguousIntentId());
            if (ambiguous.isEmpty()) {
                return response;
            }
            reclassifyCount++;
            if (reclassifyCount > properties.getMaxReclassify()) {
                throw new IllegalStateException(
                        LIMIT_CODE + ": max=" + properties.getMaxReclassify());
            }
            invalidateRouteCache(request.getConversationId());
            current = buildAugmentedRequest(request, ambiguous.get());
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        if (!properties.isEnabled()) {
            wrapped.streamQuery(request, observer);
            return;
        }
        int reclassifyCount = 0;
        ServeRequest current = request;
        while (true) {
            ReclassifyObserver probe = new ReclassifyObserver(observer, properties.getAmbiguousIntentId());
            wrapped.streamQuery(current, probe);
            if (!probe.ambiguousTriggered()) {
                return;
            }
            reclassifyCount++;
            if (reclassifyCount > properties.getMaxReclassify()) {
                observer.onError(new IllegalStateException(
                        LIMIT_CODE + ": max=" + properties.getMaxReclassify()));
                return;
            }
            invalidateRouteCache(request.getConversationId());
            current = buildAugmentedRequest(request, probe.ambiguousPayload().orElseThrow());
        }
    }

    /**
     * Clears any cached L1 route for the given conversation before a
     * reclassify retry, so the next {@code wrapped.query/streamQuery}
     * invocation re-runs L1 instead of reusing the rejected route.
     * No-op when no {@link RouteCache} is wired or the id is blank.
     *
     * @param conversationId the active conversation id
     */
    private void invalidateRouteCache(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        routeCache.ifPresent(c -> c.invalidate(conversationId));
    }

    @Override
    public void cancelActive(String conversationId) {
        wrapped.cancelActive(conversationId);
    }

    @Override
    public void resetConversation(String conversationId) {
        wrapped.resetConversation(conversationId);
    }

    private static ServeRequest buildAugmentedRequest(ServeRequest original, AmbiguousPayload payload) {
        ServeRequest augmented = new ServeRequest();
        augmented.setConversationId(original.getConversationId());
        augmented.setUserId(original.getUserId());
        augmented.setSpaceId(original.getSpaceId());
        augmented.setTenantId(original.getTenantId());
        augmented.setStream(original.isStream());
        augmented.setMetadata(new LinkedHashMap<>(original.getMetadata()));
        List<Map<String, Object>> messages = new ArrayList<>(original.getMessages());
        Map<String, Object> assistantMsg = new LinkedHashMap<>();
        assistantMsg.put("role", "assistant");
        assistantMsg.put("content", payload.responseContent());
        messages.add(assistantMsg);
        augmented.setMessages(messages);
        return augmented;
    }
}
