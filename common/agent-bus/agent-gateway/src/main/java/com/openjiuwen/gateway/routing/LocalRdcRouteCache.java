/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-local FEAT-016 route cache for gateway RDC calls. Used when the
 * registry center is briefly unavailable (Feat-Func-016 L2 §4.5).
 */
final class LocalRdcRouteCache {
    private final Duration ttl;
    private final LongSupplier clock;
    private final Map<String, SearchEntry> searchByAgent = new ConcurrentHashMap<>();
    private final Map<String, ResolveEntry> resolveByHandle = new ConcurrentHashMap<>();

    LocalRdcRouteCache(Duration ttl, LongSupplier clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void putSearch(String tenantId, String agentId, List<AgentCardRoute> routes) {
        if (routes == null || routes.isEmpty()) {
            return;
        }
        searchByAgent.put(searchKey(tenantId, agentId),
                new SearchEntry(List.copyOf(routes), clock.getAsLong()));
    }

    /**
     * Returns cached search hits, or an empty list when missing/expired.
     * Empty is never cached, so empty always means cache miss.
     */
    List<AgentCardRoute> getSearch(String tenantId, String agentId) {
        return getFresh(searchByAgent.get(searchKey(tenantId, agentId)))
                .map(SearchEntry::routes)
                .orElse(List.of());
    }

    void putResolve(String tenantId, String routeHandle, String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            return;
        }
        resolveByHandle.put(resolveKey(tenantId, routeHandle),
                new ResolveEntry(endpointUrl, clock.getAsLong()));
    }

    Optional<String> getResolve(String tenantId, String routeHandle) {
        return getFresh(resolveByHandle.get(resolveKey(tenantId, routeHandle)))
                .map(ResolveEntry::endpointUrl);
    }

    private <T extends TimedEntry> Optional<T> getFresh(T entry) {
        if (entry == null) {
            return Optional.empty();
        }
        long ageMs = clock.getAsLong() - entry.cachedAtMs();
        if (ageMs > ttl.toMillis()) {
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
        /** Epoch millis when this entry was written. */
        long cachedAtMs();
    }

    private record SearchEntry(List<AgentCardRoute> routes, long cachedAtMs) implements TimedEntry {
    }

    private record ResolveEntry(String endpointUrl, long cachedAtMs) implements TimedEntry {
    }
}
