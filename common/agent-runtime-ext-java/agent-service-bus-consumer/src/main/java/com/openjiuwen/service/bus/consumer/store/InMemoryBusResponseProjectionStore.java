/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.store;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.port.BusResponseProjectionStore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides the InMemoryBusResponseProjectionStore component.
 *
 * @since 2026-07-22
 */
public final class InMemoryBusResponseProjectionStore implements BusResponseProjectionStore {
    private final Map<String, BusResponseProjection> projections = new ConcurrentHashMap<>();
    private final Map<String, Boolean> published = new ConcurrentHashMap<>();
    private final Map<String, Sequence> sequences = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public synchronized boolean append(BusResponseProjection projection) {
        requireTenant(projection.tenantId());
        String key = key(projection.tenantId(), projection.eventId());
        if (projections.containsKey(key)) {
            return false;
        }
        enforceOrder(projection);
        projections.put(key, projection);
        published.put(key, false);
        return true;
    }

    /** {@inheritDoc} */
    @Override
    public void markPublished(String tenantId, String eventId) {
        String key = key(requireTenant(tenantId), eventId);
        if (!projections.containsKey(key)) {
            throw new IllegalStateException("projection does not exist");
        }
        published.put(key, true);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isPublished(String tenantId, String eventId) {
        return Boolean.TRUE.equals(published.get(key(requireTenant(tenantId), eventId)));
    }

    /** {@inheritDoc} */
    @Override
    public List<BusResponseProjection> pending(String tenantId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String prefix = requireTenant(tenantId) + "\u0000";
        return projections.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                .filter(e -> !Boolean.TRUE.equals(published.get(e.getKey()))).map(Map.Entry::getValue)
                .sorted((a, b) -> a.occurredAt().compareTo(b.occurredAt())).limit(limit).toList();
    }

    private void enforceOrder(BusResponseProjection projection) {
        if (projection.taskId() == null) {
            return;
        }
        String sequenceKey = projection.tenantId() + "\u0000" + projection.taskId() + "\u0000"
                + (projection.correlationId() == null ? "" : projection.correlationId());
        Sequence sequence = sequences.computeIfAbsent(sequenceKey, ignored -> new Sequence());
        String kind = projection.projectionKind();
        if (sequence.terminal && !"TERMINAL".equals(kind)) {
            throw new IllegalStateException("projection after TERMINAL is not allowed");
        }
        if (("INPUT_REQUIRED".equals(kind) || "TERMINAL".equals(kind)) && !sequence.accepted) {
            throw new IllegalStateException(kind + " requires ACCEPTED first");
        }
        if (projection.revision() > 0 && projection.revision() <= sequence.revision) {
            throw new IllegalStateException("projection revision must increase");
        }
        if ("ACCEPTED".equals(kind)) {
            sequence.accepted = true;
        }
        if (projection.revision() > 0) {
            sequence.revision = projection.revision();
        }
        if ("TERMINAL".equals(kind)) {
            sequence.terminal = true;
        }
    }

    private static String key(String tenantId, String eventId) {
        return tenantId + "\u0000" + eventId;
    }

    private static String requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        return tenantId;
    }

    private static final class Sequence {
        boolean accepted;
        boolean terminal;
        long revision;
    }
}
