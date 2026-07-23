/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import com.openjiuwen.service.bus.consumer.a2a.ProjectingTaskStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;

import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;

/**
 * Fails fast when reliable consumption is requested with ephemeral state stores.
 *
 * @since 2026-07-22
 */
public final class DurabilityGuard {
    private DurabilityGuard() {
    }

    /**
     * Performs the verify operation.
     *
     * @param reliableRequired
     *            the reliableRequired value
     * @param allowEphemeral
     *            the allowEphemeral value
     * @param admissionStore
     *            the admissionStore value
     * @param projectionStore
     *            the projectionStore value
     */
    public static void verify(boolean reliableRequired, boolean allowEphemeral, Object admissionStore,
            Object projectionStore) {
        if (!reliableRequired || allowEphemeral) {
            return;
        }
        if (admissionStore instanceof InMemoryBusTaskAdmissionStore
                || projectionStore instanceof InMemoryBusResponseProjectionStore) {
            throw new IllegalStateException("durable admission and projection stores are required");
        }
    }

    /**
     * Performs the verifyTaskStore operation.
     *
     * @param allowEphemeral
     *            the allowEphemeral value
     * @param taskStore
     *            the taskStore value
     */
    public static void verifyTaskStore(boolean allowEphemeral, TaskStore taskStore) {
        if (allowEphemeral) {
            return;
        }
        TaskStore candidate = taskStore instanceof ProjectingTaskStore projecting ? projecting.delegate() : taskStore;
        if (candidate instanceof InMemoryTaskStore) {
            throw new IllegalStateException("durable A2A TaskStore is required");
        }
    }
}
