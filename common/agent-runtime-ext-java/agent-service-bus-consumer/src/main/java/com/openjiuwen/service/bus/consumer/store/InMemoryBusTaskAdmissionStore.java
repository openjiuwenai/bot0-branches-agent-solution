/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.store;

import com.openjiuwen.service.bus.consumer.model.Admission;
import com.openjiuwen.service.bus.consumer.port.BusTaskAdmissionStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Provides an in-memory admission store for tests and explicitly ephemeral deployments.
 *
 * @since 2026-07-22
 */
public final class InMemoryBusTaskAdmissionStore implements BusTaskAdmissionStore {
    private final ConcurrentMap<String, Admission> tasks = new ConcurrentHashMap<>();

    /** {@inheritDoc} */
    @Override
    public Optional<Admission> find(String tenantId, String key) {
        return Optional.ofNullable(tasks.get(key(tenantId, key)));
    }

    /** {@inheritDoc} */
    @Override
    public Admission reserve(Admission reservation) {
        if (reservation.state() != Admission.State.RESERVED) {
            throw new IllegalArgumentException("reservation must be in RESERVED state");
        }
        return tasks.computeIfAbsent(key(reservation.tenantId(), reservation.idempotencyKey()), ignored -> reservation);
    }

    /** {@inheritDoc} */
    @Override
    public void markAdmitted(String tenantId, String key, String taskId) {
        tasks.computeIfPresent(key(tenantId, key),
                (ignored, admission) -> new Admission(admission.tenantId(), admission.idempotencyKey(),
                        admission.requestDigest(), taskId, admission.sourceFamily(), admission.correlationId(),
                        admission.traceId(), admission.sourceServiceId(), admission.targetServiceId(),
                        admission.routeHandle(), Admission.State.ADMITTED));
    }

    /** {@inheritDoc} */
    @Override
    public Optional<Admission> findByTaskId(String tenantId, String taskId) {
        return tasks.values().stream().filter(a -> a.tenantId().equals(tenantId) && a.taskId().equals(taskId))
                .findFirst();
    }

    /** {@inheritDoc} */
    @Override
    public List<Admission> list(String tenantId, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return tasks.values().stream().filter(a -> a.tenantId().equals(tenantId)).limit(limit).toList();
    }

    private static String key(String tenant, String idempotency) {
        return tenant + "\u0000" + idempotency;
    }
}
