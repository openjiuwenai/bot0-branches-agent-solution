/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.RouteResolution;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Process-local FEAT-016 route cache for RDC itself. When PostgreSQL is briefly
 * unavailable, successful search / resolve results within the TTL are returned
 * to callers so DB outage is shielded at the registry boundary (Feat-Func-016
 * L2 §4.5 stage-1; two-level degradation level 1).
 *
 * <p>Does not cache empty search results. Keys always include {@code tenantId}
 * — no cross-tenant reuse.
 */
final class RdcLocalRouteCache {
    private final Duration ttl;
    private final LongSupplier clock;
    private final Map<String, SearchEntry> searchByKey = new ConcurrentHashMap<>();
    private final Map<String, ResolveEntry> resolveByKey = new ConcurrentHashMap<>();

    RdcLocalRouteCache(Duration ttl, LongSupplier clock) {
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    void putSearch(String dimension, String tenantId, String value, String contractVersion,
                   List<AgentCardDto> routes) {
        if (routes == null || routes.isEmpty()) {
            return;
        }
        searchByKey.put(searchKey(dimension, tenantId, value, contractVersion),
                new SearchEntry(List.copyOf(routes), clock.getAsLong()));
    }

    /**
     * Returns cached search hits, or an empty list when missing/expired.
     * Empty is never cached, so empty always means cache miss.
     */
    List<AgentCardDto> getSearch(String dimension, String tenantId, String value,
                                 String contractVersion) {
        return getFresh(searchByKey.get(searchKey(dimension, tenantId, value, contractVersion)))
                .map(SearchEntry::routes)
                .orElse(List.of());
    }

    void putResolve(String tenantId, String routeHandle, RouteResolution resolution) {
        if (resolution == null || resolution.endpointUrl() == null || resolution.endpointUrl().isBlank()) {
            return;
        }
        resolveByKey.put(resolveKey(tenantId, routeHandle),
                new ResolveEntry(resolution, clock.getAsLong()));
    }

    Optional<RouteResolution> getResolve(String tenantId, String routeHandle) {
        return getFresh(resolveByKey.get(resolveKey(tenantId, routeHandle)))
                .map(ResolveEntry::resolution);
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

    private static String searchKey(String dimension, String tenantId, String value,
                                    String contractVersion) {
        return dimension + '\0' + tenantId + '\0' + value + '\0'
                + (contractVersion == null ? "" : contractVersion);
    }

    private static String resolveKey(String tenantId, String routeHandle) {
        return tenantId + '\0' + routeHandle;
    }

    private interface TimedEntry {
        /** Epoch millis when this entry was written. */
        long cachedAtMs();
    }

    private record SearchEntry(List<AgentCardDto> routes, long cachedAtMs) implements TimedEntry {
    }

    private record ResolveEntry(RouteResolution resolution, long cachedAtMs) implements TimedEntry {
    }
}
