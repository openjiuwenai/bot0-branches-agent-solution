/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;

import java.util.List;

/**
 * Defines the BusResponseProjectionStore contract.
 *
 * @since 2026-07-22
 */
public interface BusResponseProjectionStore {
    /**
     * Atomically appends a new event.
     *
     * @param projection
     *            projection to append
     *
     * @return false when the event already exists
     */
    boolean append(BusResponseProjection projection);

    /**
     * Marks an event as published.
     *
     * @param tenantId
     *            tenant identity
     * @param eventId
     *            event identity
     */
    void markPublished(String tenantId, String eventId);

    /**
     * Checks whether an event has been published.
     *
     * @param tenantId
     *            tenant identity
     * @param eventId
     *            event identity
     *
     * @return true when the event has been published
     */
    boolean isPublished(String tenantId, String eventId);

    /**
     * Lists unpublished events in a tenant scope.
     *
     * @param tenantId
     *            tenant identity
     * @param limit
     *            maximum result count
     *
     * @return unpublished events in the tenant scope
     */
    default List<BusResponseProjection> pending(String tenantId, int limit) {
        return List.of();
    }
}
