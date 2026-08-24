/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Starts broker polling only when a host supplies a concrete broker port.
 *
 * @since 2026-07-22
 */
public final class BrokerConsumerLifecycle implements SmartLifecycle {
    private static final Logger LOG = LoggerFactory.getLogger(BrokerConsumerLifecycle.class);

    private final BrokerDeliveryLoop loop;
    private final ScheduledExecutorService executor;
    private final long intervalMillis;
    private volatile boolean running;

    /**
     * Creates a new instance.
     *
     * @param loop
     *            the loop value
     * @param intervalMillis
     *            the intervalMillis value
     */
    public BrokerConsumerLifecycle(BrokerDeliveryLoop loop, long intervalMillis) {
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("poll interval must be positive");
        }
        this.loop = loop;
        this.intervalMillis = intervalMillis;
        this.executor = BusExecutors.singleThreadScheduler("agent-bus-consumer");
    }

    /** {@inheritDoc} */
    @Override
    public void start() {
        if (!running) {
            running = true;
            scheduleNext(0);
        }
    }

    /**
     * Schedules exactly one tick.
     *
     * <p>Deliberately not {@code scheduleWithFixedDelay}: that API cancels every remaining execution
     * as soon as a task throws, which would stop Bus consumption for good while {@link #isRunning()}
     * keeps reporting {@code true}. Re-arming from the {@code finally} block of {@link #tick()}
     * survives any throwable without widening that method's catch clause.
     *
     * @param delayMillis delay before the next tick
     */
    private void scheduleNext(long delayMillis) {
        if (!running || executor.isShutdown()) {
            return;
        }
        try {
            executor.schedule(this::tick, delayMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException rejected) {
            LOG.debug("Broker delivery loop stopped; no further ticks scheduled", rejected);
        }
    }

    private void tick() {
        try {
            loop.tick();
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOG.warn("Broker delivery tick failed; the next tick will retry", failure);
        } finally {
            scheduleNext(intervalMillis);
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
            LOG.warn("Interrupted while draining the broker consumer", interrupted);
            executor.shutdownNow();
        }
    }

    /** {@inheritDoc} */
    @Override
    public boolean isRunning() {
        return running;
    }

    /** {@inheritDoc} */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
