/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.routing;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gateway-internal short-lived {@code taskId -> routeHandle} index (FEAT-011 L2
 * §4.4 P4 / §5). Written by the create path on first taskId, read (only) by the
 * resume path to route back to the original Task owner. NOT a RDC query and NOT
 * exposed to the client.
 *
 * <p>730 in-memory single-process (decision D4); multi-instance Gateway would
 * need shared storage — deliberately out of 730 scope.
 *
 * @since 0.1.0
 */
@Component
public class StickyIndex {
    private final ConcurrentHashMap<String, String> index = new ConcurrentHashMap<>();

    /**
     * Bind a task to the route handle that owns it (create path, first taskId).
     *
     * @param taskId       runtime task id
     * @param routeHandle  opaque route handle of the owning instance
     */
    public void put(String taskId, String routeHandle) {
        index.put(taskId, routeHandle);
    }

    /**
     * Look up the owning route handle for a task (resume path, read-only).
     *
     * @param taskId runtime task id
     * @return the bound route handle, or empty if unknown / expired
     */
    public Optional<String> find(String taskId) {
        return Optional.ofNullable(index.get(taskId));
    }

    /**
     * Clear all bindings (test / admin helper).
     */
    public void clear() {
        index.clear();
    }
}
