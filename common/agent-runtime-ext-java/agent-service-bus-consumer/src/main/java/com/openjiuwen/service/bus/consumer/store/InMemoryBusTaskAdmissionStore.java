/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.store;

import com.openjiuwen.service.bus.consumer.model.Admission;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Provides the SDK-owned in-memory admission store.
 *
 * @since 2026-07-22
 */
public final class InMemoryBusTaskAdmissionStore {
    private final ConcurrentMap<String, Admission> tasks = new ConcurrentHashMap<>();

    /**
     * Finds an admission by its idempotency scope.
     *
     * @param tenantId
     *            tenant identity
     * @param key
     *            request idempotency key
     *
     * @return the matching admission, if present
     */
    public Optional<Admission> find(String tenantId, String key) {
        return Optional.ofNullable(tasks.get(key(tenantId, key)));
    }

    /**
     * Atomically stores a reservation unless one already exists.
     *
     * @param reservation
     *            reservation candidate
     *
     * @return the existing or newly stored reservation
     */
    public Admission reserve(Admission reservation) {
        if (reservation.state() != Admission.State.RESERVED) {
            throw new IllegalArgumentException("reservation must be in RESERVED state");
        }
        return tasks.computeIfAbsent(key(reservation.tenantId(), reservation.idempotencyKey()), ignored -> reservation);
    }

    /**
     * Marks a reservation as admitted.
     *
     * @param tenantId
     *            tenant identity
     * @param key
     *            request idempotency key
     * @param taskId
     *            admitted Task identity
     */
    public void markAdmitted(String tenantId, String key, String taskId) {
        tasks.computeIfPresent(key(tenantId, key),
                (ignored, admission) -> new Admission(admission.tenantId(), admission.idempotencyKey(),
                        admission.requestDigest(), taskId, admission.sourceFamily(), admission.correlationId(),
                        admission.traceId(), admission.sourceServiceId(), admission.targetServiceId(),
                        admission.routeHandle(), Admission.State.ADMITTED));
    }

    /**
     * Finds an admission by Task identity.
     *
     * @param tenantId
     *            tenant identity
     * @param taskId
     *            Task identity
     *
     * @return the admission associated with the Task, if present
     */
    public Optional<Admission> findByTaskId(String tenantId, String taskId) {
        return tasks.values().stream().filter(a -> a.tenantId().equals(tenantId) && a.taskId().equals(taskId))
                .findFirst();
    }

    /**
     * Lists admissions in a tenant scope.
     *
     * @param tenantId
     *            tenant identity
     * @param limit
     *            maximum result count
     *
     * @return admissions in the requested tenant scope
     */
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
