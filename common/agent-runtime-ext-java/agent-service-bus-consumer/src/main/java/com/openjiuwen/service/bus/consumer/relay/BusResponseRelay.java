/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.relay;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.observability.BusConsumerTelemetry;
import com.openjiuwen.service.bus.consumer.port.BusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.port.RuntimeBusResponsePublisher;
import com.openjiuwen.service.bus.consumer.runtime.BusConcurrencyGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Relays persisted response projections with bounded retry.
 *
 * @since 2026-07-22
 */
public final class BusResponseRelay implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(BusResponseRelay.class);

    private final BusResponseProjectionStore store;
    private final RuntimeBusResponsePublisher publisher;
    private final ScheduledExecutorService scheduler;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final BusConsumerTelemetry telemetry;
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
    public BusResponseRelay(BusResponseProjectionStore store, RuntimeBusResponsePublisher publisher,
            ScheduledExecutorService scheduler, int maxAttempts, Duration initialBackoff) {
        this(store, publisher, scheduler, maxAttempts, initialBackoff, new BusConsumerTelemetry(null, null),
                new BusConcurrencyGuard(16, 16, 16, 64));
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
     * @param telemetry
     *            the telemetry value
     */
    public BusResponseRelay(BusResponseProjectionStore store, RuntimeBusResponsePublisher publisher,
            ScheduledExecutorService scheduler, int maxAttempts, Duration initialBackoff,
            BusConsumerTelemetry telemetry) {
        this(store, publisher, scheduler, maxAttempts, initialBackoff, telemetry,
                new BusConcurrencyGuard(16, 16, 16, 64));
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
     * @param telemetry
     *            the telemetry value
     * @param concurrency
     *            the concurrency value
     */
    public BusResponseRelay(BusResponseProjectionStore store, RuntimeBusResponsePublisher publisher,
            ScheduledExecutorService scheduler, int maxAttempts, Duration initialBackoff,
            BusConsumerTelemetry telemetry, BusConcurrencyGuard concurrency) {
        if (maxAttempts < 1 || initialBackoff.isNegative() || initialBackoff.isZero()) {
            throw new IllegalArgumentException();
        }
        this.store = Objects.requireNonNull(store);
        this.publisher = Objects.requireNonNull(publisher);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.telemetry = Objects.requireNonNull(telemetry);
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
        boolean appended = telemetry.observe("projection.append", family(projection), () -> store.append(projection));
        if (!appended) {
            return false;
        }
        telemetry.projectionAppended();
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
            telemetry.observe("projection.publish", family(projection),
                    () -> concurrency.call(BusConcurrencyGuard.Lane.PROJECTION, () -> {
                        publisher.publish(projection);
                        return null;
                    }));
            store.markPublished(projection.tenantId(), projection.eventId());
            telemetry.projectionPublished();
            telemetry.projection(projection.eventType(), "published");
            telemetry.projectionLag(projection.eventType(),
                    Duration.between(projection.occurredAt(), java.time.Instant.now()).toNanos());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            telemetry.projection(projection.eventType(), "failed");
            LOG.warn("Projection publish attempt {} failed for event {}", attempt, projection.eventId(), failure);
            if (attempt < maxAttempts) {
                scheduler.schedule(() -> attempt(projection, attempt + 1),
                        initialBackoff.toMillis() * (1L << Math.min(attempt - 1, 20)), TimeUnit.MILLISECONDS);
            }
        }
    }

    private static String family(BusResponseProjection projection) {
        return projection.eventType().startsWith("A2A") ? "a2a" : "invocation";
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
