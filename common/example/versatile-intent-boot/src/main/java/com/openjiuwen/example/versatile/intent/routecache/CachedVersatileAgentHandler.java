/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Decorates an {@link AgentHandler} (typically {@code VersatileAgentHandler}
 * acting as L1) with a per-conversation route cache. On the first turn the
 * delegate runs and the emitted {@code a2a_delegate} interrupt is captured
 * into {@link RouteCache}. On subsequent turns within the same conversation
 * (and before TTL expiry) a synthetic {@code a2a_delegate} payload is
 * produced without invoking the delegate, skipping the L1 Versatile workflow.
 *
 * <p>Cache invalidation hooks:
 * <ul>
 *   <li>{@link #clearSession(String)} — called by the runtime's
 *       {@code resetConversation} flow — removes the cache entry.</li>
 *   <li>TTL expiry evicts stale entries automatically.</li>
 * </ul>
 *
 * @since 2026-07-25
 */
public final class CachedVersatileAgentHandler implements AgentHandler {
    private final AgentHandler delegate;
    private final RouteCache cache;
    private final RouteCacheProperties properties;
    private final LongSupplier nowMillis;

    public CachedVersatileAgentHandler(AgentHandler delegate, RouteCache cache,
                                       RouteCacheProperties properties, LongSupplier nowMillis) {
        this.delegate = delegate;
        this.cache = cache;
        this.properties = properties;
        this.nowMillis = nowMillis;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            return delegate.query(request);
        }
        Optional<CachedRoute> hit = cache.get(conversationId);
        if (hit.isPresent()) {
            return syntheticResponse(request, hit.get());
        }
        QueryResponse response = delegate.query(request);
        captureIfA2aDelegate(conversationId, response.getResult());
        return response;
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            delegate.streamQuery(request, observer);
            return;
        }
        Optional<CachedRoute> hit = cache.get(conversationId);
        if (hit.isPresent()) {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                    A2aDelegatePayload.buildSyntheticPayload(
                            hit.get().agentName(), "", request.lastUserQuery(), true)));
            observer.onComplete();
            return;
        }
        CacheInspectingObserver wrapped = new CacheInspectingObserver(observer, conversationId);
        delegate.streamQuery(request, wrapped);
    }

    @Override
    public void clearSession(String conversationId) {
        delegate.clearSession(conversationId);
        if (conversationId != null && !conversationId.isBlank()) {
            cache.invalidate(conversationId);
        }
    }

    @Override
    public void start() {
        delegate.start();
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    private QueryResponse syntheticResponse(ServeRequest request, CachedRoute route) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", "");
        result.put("_interrupt", A2aDelegatePayload.buildSyntheticPayload(
                route.agentName(), "", request.lastUserQuery(), false));
        QueryResponse response = new QueryResponse();
        response.setConversationId(request.getConversationId());
        response.setResult(result);
        return response;
    }

    @SuppressWarnings("unchecked")
    private void captureIfA2aDelegate(String conversationId, Object resultObj) {
        if (!(resultObj instanceof Map<?, ?> result)) {
            return;
        }
        Optional<A2aDelegatePayload.Parsed> parsed = A2aDelegatePayload.fromResultMap((Map<String, Object>) result);
        if (parsed.isEmpty()) {
            return;
        }
        long expiresAt = nowMillis.getAsLong() + properties.getTtl().toMillis();
        cache.put(conversationId, new CachedRoute(parsed.get().agentName(), parsed.get().responseContent(), expiresAt));
    }

    private final class CacheInspectingObserver implements QueryStreamObserver {
        private final QueryStreamObserver downstream;
        private final String conversationId;
        private volatile A2aDelegatePayload.Parsed captured;

        CacheInspectingObserver(QueryStreamObserver downstream, String conversationId) {
            this.downstream = downstream;
            this.conversationId = conversationId;
        }

        @Override
        public void onNext(QueryChunk chunk) {
            Optional<A2aDelegatePayload.Parsed> parsed = A2aDelegatePayload.fromChunkData(chunk.getData());
            if (parsed.isPresent()) {
                captured = parsed.get();
            }
            downstream.onNext(chunk);
        }

        @Override
        public void onError(Throwable t) {
            downstream.onError(t);
        }

        @Override
        public void onComplete() {
            if (captured != null) {
                long expiresAt = nowMillis.getAsLong() + properties.getTtl().toMillis();
                cache.put(conversationId,
                        new CachedRoute(captured.agentName(), captured.responseContent(), expiresAt));
            }
            downstream.onComplete();
        }

        @Override
        public boolean isCancelled() {
            return downstream.isCancelled();
        }
    }
}
