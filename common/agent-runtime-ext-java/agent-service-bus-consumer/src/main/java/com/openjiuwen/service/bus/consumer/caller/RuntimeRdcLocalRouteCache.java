/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-local FEAT-016 route cache for runtime registry calls.
 */
final class RuntimeRdcLocalRouteCache {
    private final Duration ttl;
    private final LongSupplier clock;
    private final Map<String, SearchEntry> searchByAgent = new ConcurrentHashMap<>();
    private final Map<String, ResolveEntry> resolveByHandle = new ConcurrentHashMap<>();

    RuntimeRdcLocalRouteCache(Duration ttl, LongSupplier clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    void putSearch(String tenantId, String agentId, List<RuntimeRdcClient.RouteCandidate> routes) {
        if (routes == null || routes.isEmpty()) {
            return;
        }
        searchByAgent.put(searchKey(tenantId, agentId),
                new SearchEntry(List.copyOf(routes), clock.getAsLong()));
    }

    Optional<List<RuntimeRdcClient.RouteCandidate>> getSearch(String tenantId, String agentId) {
        return getFresh(searchByAgent.get(searchKey(tenantId, agentId)))
                .map(SearchEntry::routes);
    }

    void putResolve(String tenantId, String routeHandle, RuntimeRdcClient.ResolvedRoute route) {
        resolveByHandle.put(resolveKey(tenantId, routeHandle),
                new ResolveEntry(route, clock.getAsLong()));
    }

    Optional<RuntimeRdcClient.ResolvedRoute> getResolve(String tenantId, String routeHandle) {
        return getFresh(resolveByHandle.get(resolveKey(tenantId, routeHandle)))
                .map(ResolveEntry::route);
    }

    private <T extends TimedEntry> Optional<T> getFresh(T entry) {
        if (entry == null) {
            return Optional.empty();
        }
        if (clock.getAsLong() - entry.cachedAtMs() > ttl.toMillis()) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    private static String searchKey(String tenantId, String agentId) {
        return tenantId + '\0' + agentId;
    }

    private static String resolveKey(String tenantId, String routeHandle) {
        return tenantId + '\0' + routeHandle;
    }

    private interface TimedEntry {
        long cachedAtMs();
    }

    private record SearchEntry(List<RuntimeRdcClient.RouteCandidate> routes, long cachedAtMs)
            implements TimedEntry {
    }

    private record ResolveEntry(RuntimeRdcClient.ResolvedRoute route, long cachedAtMs)
            implements TimedEntry {
    }
}
