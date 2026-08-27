/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.relay;

import com.openjiuwen.service.bus.consumer.a2a.TaskStoreProjectionPostProcessor;
import com.openjiuwen.service.bus.consumer.runtime.BusExecutors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically repairs unpublished response projections and missed Task state projections.
 *
 * @since 2026-07-22
 */
public final class BusProjectionRepairScheduler implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(BusProjectionRepairScheduler.class);

    private final BusProjectionRepairer repairer;
    private final long intervalMillis;
    private final TaskStoreProjectionPostProcessor taskProjector;
    private final String tenantId;
    private final ScheduledExecutorService executor = BusExecutors.singleThreadScheduler("agent-bus-projection-repair");
    private volatile boolean running;

    /**
     * Creates a new instance.
     *
     * @param repairer
     *            the repairer value
     * @param intervalMillis
     *            the intervalMillis value
     */
    public BusProjectionRepairScheduler(BusProjectionRepairer repairer, long intervalMillis) {
        this(repairer, intervalMillis, null, null);
    }

    /**
     * Creates a new instance.
     *
     * @param repairer
     *            the repairer value
     * @param intervalMillis
     *            the intervalMillis value
     * @param taskProjector
     *            the taskProjector value
     * @param tenantId
     *            the tenantId value
     */
    public BusProjectionRepairScheduler(BusProjectionRepairer repairer, long intervalMillis,
            TaskStoreProjectionPostProcessor taskProjector, String tenantId) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("repair interval must be positive");
        }
        this.repairer = repairer;
        this.intervalMillis = intervalMillis;
        this.taskProjector = taskProjector;
        this.tenantId = tenantId;
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        if (!running) {
            running = true;
            scheduleNext();
        }
    }

    /**
     * Schedules exactly one repair round.
     *
     * <p>Deliberately not {@code scheduleWithFixedDelay}: repair rounds hit the projection store, and
     * a single storage failure would otherwise cancel the whole schedule while {@link #isRunning()}
     * keeps reporting {@code true}. Re-arming from the {@code finally} block of {@link #repair()}
     * survives any throwable, including the storage exceptions this class cannot name.
     */
    private void scheduleNext() {
        if (!running || executor.isShutdown()) {
            return;
        }
        try {
            executor.schedule(this::repair, intervalMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            LOG.debug("Projection repair stopped; no further rounds scheduled", rejected);
        }
    }

    private void repair() {
        try {
            repairer.repair(100);
            if (taskProjector != null && tenantId != null) {
                taskProjector.repair(tenantId, 100);
            }
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOG.warn("Projection repair round failed; the next round will retry", failure);
        } finally {
            scheduleNext();
        }
    }

    /** {@inheritDoc} */
    @Override
    public void stop() {
        running = false;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            LOG.warn("Interrupted while draining the projection repair scheduler", interrupted);
            executor.shutdownNow();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() {
        return running;
    }
}
