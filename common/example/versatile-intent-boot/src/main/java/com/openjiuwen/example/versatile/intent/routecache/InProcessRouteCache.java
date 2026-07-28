/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-local {@link RouteCache} backed by a {@link ConcurrentHashMap}.
 * Entries are expired lazily on read; no background sweep thread is used.
 *
 * @since 2026-07-25
 */
public final class InProcessRouteCache implements RouteCache {
    private final ConcurrentHashMap<String, CachedRoute> store = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final LongSupplier nowMillis;

    public InProcessRouteCache(Duration ttl, LongSupplier nowMillis) {
        this.ttl = ttl;
        this.nowMillis = nowMillis;
    }

    @Override
    public Optional<CachedRoute> get(String conversationId) {
        CachedRoute route = store.get(conversationId);
        if (route == null) {
            return Optional.empty();
        }
        if (route.isExpired(nowMillis.getAsLong())) {
            store.remove(conversationId, route);
            return Optional.empty();
        }
        return Optional.of(route);
    }

    @Override
    public void put(String conversationId, CachedRoute route) {
        store.put(conversationId, route);
    }

    @Override
    public void invalidate(String conversationId) {
        store.remove(conversationId);
    }
}