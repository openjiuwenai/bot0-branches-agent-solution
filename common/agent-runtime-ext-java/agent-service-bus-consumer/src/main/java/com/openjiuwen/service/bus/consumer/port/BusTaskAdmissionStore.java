/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

import com.openjiuwen.service.bus.consumer.model.Admission;

import java.util.List;
import java.util.Optional;

/**
 * Stores idempotent bus-to-Task admission records.
 *
 * @since 2026-07-22
 */
public interface BusTaskAdmissionStore {
    /**
     * Finds an admission by its idempotency scope.
     *
     * @param tenantId
     *            tenant identity
     * @param idempotencyKey
     *            request idempotency key
     *
     * @return the matching admission, if present
     */
    Optional<Admission> find(String tenantId, String idempotencyKey);

    /**
     * Atomically stores a reservation unless one already exists.
     *
     * @param reservation
     *            reservation candidate
     *
     * @return the existing or newly stored reservation
     */
    Admission reserve(Admission reservation);

    /**
     * Marks a reservation as admitted.
     *
     * @param tenantId
     *            tenant identity
     * @param idempotencyKey
     *            request idempotency key
     * @param taskId
     *            admitted Task identity
     */
    void markAdmitted(String tenantId, String idempotencyKey, String taskId);

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
    default Optional<Admission> findByTaskId(String tenantId, String taskId) {
        return Optional.empty();
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
    default List<Admission> list(String tenantId, int limit) {
        return List.of();
    }
}
