/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gateway-internal {@code taskId -> routeHandle} index (FEAT-011 L2 §4.4 P4 / §5).
 * Written by the create path on first taskId, read (only) by the resume / GetTask /
 * SubscribeToTask path to route back to the original Task owner. NOT a RDC query
 * and NOT exposed to the client.
 *
 * <p>v0830: adds TTL + periodic cleanup (supplement info 1). Entries expire after
 * {@code gateway.routing.sticky-ttl-ms} (default 1h); a background sweeper evicts
 * expired entries every {@code gateway.routing.sticky-cleanup-interval-ms} (default
 * 5min) to avoid unbounded growth in long-running processes.
 *
 * <p>In-memory single-process (decision D4); multi-instance Gateway would need
 * shared storage (Redis) — deliberately out of v0830 scope.
 *
 * @since 0.1.0
 */
@Component
public class StickyIndex {
    private final ConcurrentHashMap<String, Entry> index = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final ScheduledExecutorService sweeper;

    /**
     * No-arg constructor with default TTL (1h) and cleanup interval (5min).
     * Used by tests and when Spring wiring is unavailable.
     */
    public StickyIndex() {
        this(3_600_000L, 300_000L);
    }

    /**
     * Construct with TTL and cleanup interval from config.
     *
     * @param ttlMillis        entry TTL (default 3600000 = 1h)
     * @param cleanupIntervalMillis sweeper interval (default 300000 = 5min)
     */
    public StickyIndex(
            @Value("${gateway.routing.sticky-ttl-ms:3600000}") long ttlMillis,
            @Value("${gateway.routing.sticky-cleanup-interval-ms:300000}") long cleanupIntervalMillis) {
        this.ttlMillis = ttlMillis;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Executors.defaultThreadFactory().newThread(r);
            t.setName("sticky-index-sweeper");
            t.setDaemon(true);
            t.setUncaughtExceptionHandler((thread, ex) -> { });
            return t;
        });
        if (cleanupIntervalMillis > 0) {
            sweeper.scheduleAtFixedRate(this::evictExpired, cleanupIntervalMillis,
                    cleanupIntervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Bind a task to the owning instance's route handle and target service id (create path,
     * first taskId). Refreshes TTL on re-write. The target service id lets the BUS GetTask path
     * route a query to the owner over the bus without re-resolving by agentId (a GetTask body
     * carries only {@code params.id}, no agentId).
     *
     * @param taskId         runtime task id
     * @param routeHandle    opaque route handle of the owning instance
     * @param targetServiceId owning instance's target service id (for BUS query routing)
     */
    public void put(String taskId, String routeHandle, String targetServiceId) {
        index.put(taskId, new Entry(routeHandle, targetServiceId, System.currentTimeMillis() + ttlMillis));
    }

    /**
     * Look up the owning route handle for a task (resume / GetTask / SubscribeToTask
     * path, read-only). Returns empty if unknown or expired (lazy expiry on read).
     *
     * @param taskId runtime task id
     * @return the bound route handle, or empty if unknown / expired
     */
    public Optional<String> find(String taskId) {
        return lookup(taskId).map(Entry::routeHandle);
    }

    /**
     * Look up the owning route handle AND target service id for a task (BUS GetTask path —
     * a query carries no agentId, so the owner's target service id must come from the binding
     * written at create time, not a default-agent RDC re-search). Returns empty if unknown or
     * expired (lazy expiry on read).
     *
     * @param taskId runtime task id
     * @return the bound owner (route handle + target service id), or empty if unknown / expired
     */
    public Optional<Owner> findOwner(String taskId) {
        return lookup(taskId).map(e -> new Owner(e.routeHandle(), e.targetServiceId()));
    }

    private Optional<Entry> lookup(String taskId) {
        Entry e = index.get(taskId);
        if (e == null) {
            return Optional.empty();
        }
        if (e.expireAt() < System.currentTimeMillis()) {
            index.remove(taskId, e);
            return Optional.empty();
        }
        return Optional.of(e);
    }

    /**
     * Clear all bindings (test / admin helper).
     */
    public void clear() {
        index.clear();
    }

    /**
     * Evict all expired entries (background sweeper).
     */
    private void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Entry>> it = index.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> en = it.next();
            if (en.getValue().expireAt() < now) {
                it.remove();
            }
        }
    }

    record Entry(String routeHandle, String targetServiceId, long expireAt) {
    }

    /** Owning instance binding for a BUS query (route handle + target service id). */
    public record Owner(String routeHandle, String targetServiceId) {
    }
}
