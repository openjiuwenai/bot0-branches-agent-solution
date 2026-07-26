/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.relay;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.runtime.BusConcurrencyGuard;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Relays persisted response projections with bounded retry.
 *
 * @since 2026-07-22
 */
public final class BusResponseRelay implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BusResponseRelay.class);

    private final InMemoryBusResponseProjectionStore store;
    private final Consumer<BusResponseProjection> publisher;
    private final ScheduledExecutorService scheduler;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final BusConcurrencyGuard concurrency;

    /**
     * Creates a new instance.
     *
     * @param store
     *            the store value
     * @param publisher
     *            the publisher value
     * @param scheduler
     *            the scheduler value
     * @param maxAttempts
     *            the maxAttempts value
     * @param initialBackoff
     *            the initialBackoff value
     */
    public BusResponseRelay(InMemoryBusResponseProjectionStore store, Consumer<BusResponseProjection> publisher,
            ScheduledExecutorService scheduler, int maxAttempts, Duration initialBackoff) {
        this(store, publisher, scheduler, maxAttempts, initialBackoff, new BusConcurrencyGuard(16, 16, 16, 64));
    }

    /**
     * Creates a new instance.
     *
     * @param store
     *            the store value
     * @param publisher
     *            the publisher value
     * @param scheduler
     *            the scheduler value
     * @param maxAttempts
     *            the maxAttempts value
     * @param initialBackoff
     *            the initialBackoff value
     * @param concurrency
     *            the concurrency value
     */
    public BusResponseRelay(InMemoryBusResponseProjectionStore store, Consumer<BusResponseProjection> publisher,
            ScheduledExecutorService scheduler, int maxAttempts, Duration initialBackoff,
            BusConcurrencyGuard concurrency) {
        if (maxAttempts < 1 || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException();
        }
        this.store = Objects.requireNonNull(store);
        this.publisher = Objects.requireNonNull(publisher);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.concurrency = Objects.requireNonNull(concurrency);
    }

    /**
     * Performs the submit operation.
     *
     * @param projection
     *            the projection value
     *
     * @return the operation result
     */
    public boolean submit(BusResponseProjection projection) {
        boolean appended = store.append(projection);
        if (!appended) {
            return false;
        }
        attempt(projection, 1);
        return true;
    }

    /**
     * Performs the retry operation.
     *
     * @param projection
     *            the projection value
     */
    public void retry(BusResponseProjection projection) {
        attempt(projection, 1);
    }

    private void attempt(BusResponseProjection projection, int attempt) {
        if (store.isPublished(projection.tenantId(), projection.eventId())) {
            return;
        }
        try {
            concurrency.call(BusConcurrencyGuard.Lane.PROJECTION, () -> {
                publisher.accept(projection);
                return null;
            });
            store.markPublished(projection.tenantId(), projection.eventId());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOG.warn("Projection publish attempt {} failed for event {}", attempt, projection.eventId(), failure);
            if (attempt < maxAttempts) {
                scheduler.schedule(() -> attempt(projection, attempt + 1),
                        initialBackoff.toMillis() * (1L << Math.min(attempt - 1, 20)), TimeUnit.MILLISECONDS);
            }
        }
    }
    /** {@inheritDoc} */
    @Override
    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            LOG.warn("Interrupted while draining the response relay", interrupted);
            scheduler.shutdownNow();
        }
    }
}
