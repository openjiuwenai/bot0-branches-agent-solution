/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task-level admission controller (DFX-002).
 *
 * <p>Uses an AtomicInteger with CAS to enforce a configurable maximum number
 * of concurrent tasks. When {@code maxConcurrentTasks < 0} the gate is
 * unlimited and {@code tryAcquire()} always returns {@code true}.
 *
 * @since 0.1.0
 */
public class TaskAdmissionControl implements TaskAdmissionGate {

    private final int maxConcurrentTasks;

    private final AtomicInteger currentCount = new AtomicInteger(0);

    private volatile boolean shutdown = false;

    /**
     * Creates an admission control with the given limit.
     *
     * @param maxConcurrentTasks maximum concurrent tasks; {@code -1} means unlimited
     */
    public TaskAdmissionControl(int maxConcurrentTasks) {
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    @Override
    public boolean tryAcquire() {
        if (shutdown) {
            return false;
        }
        if (maxConcurrentTasks < 0) {
            return true;
        }
        while (true) {
            int current = currentCount.get();
            if (current >= maxConcurrentTasks) {
                return false;
            }
            if (currentCount.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    @Override
    public void release() {
        if (maxConcurrentTasks < 0) {
            return;
        }
        currentCount.updateAndGet(v -> Math.max(0, v - 1));
    }

    @Override
    public int currentCount() {
        return currentCount.get();
    }

    @Override
    public int limit() {
        return maxConcurrentTasks;
    }

    @Override
    public void shutdown() {
        this.shutdown = true;
    }

    @Override
    public void reset() {
        this.shutdown = false;
        currentCount.set(0);
    }

    /**
     * Returns the configured maximum concurrent tasks.
     *
     * @return max concurrent tasks; {@code -1} means unlimited
     */
    public int maxConcurrentTasks() {
        return maxConcurrentTasks;
    }
}
