/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

/**
 * Re-observes persisted Tasks after admission and during repair scans.
 *
 * @since 2026-07-22
 */
public interface BusTaskStateProjector {
    /**
     * Projects the current persisted Task state.
     *
     * @param tenantId
     *            tenant identity
     * @param taskId
     *            Task identity
     */
    void projectCurrent(String tenantId, String taskId);

    /**
     * Repairs missed Task-state projections.
     *
     * @param tenantId
     *            tenant identity
     * @param limit
     *            maximum admission records to inspect
     *
     * @return number of admission records inspected
     */
    int repair(String tenantId, int limit);
}
