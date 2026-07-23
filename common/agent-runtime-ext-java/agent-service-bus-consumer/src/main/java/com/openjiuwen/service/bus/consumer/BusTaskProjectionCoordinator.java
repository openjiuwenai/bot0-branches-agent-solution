/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.observability.BusConsumerTelemetry;
import com.openjiuwen.service.bus.consumer.port.BusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.port.RuntimeBusResponsePublisher;
import com.openjiuwen.service.bus.consumer.relay.BusResponseRelay;
import com.openjiuwen.service.bus.consumer.runtime.BusConcurrencyGuard;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes stable, idempotent Task facts; it never carries token chunks or SSE frames.
 *
 * @since 2026-07-22
 */
public final class BusTaskProjectionCoordinator {
    private static final Logger LOG = LoggerFactory.getLogger(BusTaskProjectionCoordinator.class);

    private final BusResponseProjectionStore store;
    private final RuntimeBusResponsePublisher publisher;
    private final BusResponseRelay relay;
    private final BusConsumerTelemetry telemetry;
    private final BusConcurrencyGuard concurrency;

    /**
     * Creates a new instance.
     *
     * @param store
     *            the store value
     * @param publisher
     *            the publisher value
     */
    public BusTaskProjectionCoordinator(BusResponseProjectionStore store, RuntimeBusResponsePublisher publisher) {
        this(store, publisher, null, new BusConsumerTelemetry(null, null), new BusConcurrencyGuard(16, 16, 16, 64));
    }

    /**
     * Creates a new instance.
     *
     * @param store
     *            the store value
     * @param publisher
     *            the publisher value
     * @param relay
     *            the relay value
     */
    public BusTaskProjectionCoordinator(BusResponseProjectionStore store, RuntimeBusResponsePublisher publisher,
            BusResponseRelay relay) {
        this(store, publisher, relay, new BusConsumerTelemetry(null, null), new BusConcurrencyGuard(16, 16, 16, 64));
    }

    /**
     * Creates a new instance.
     *
     * @param store
     *            the store value
     * @param publisher
     *            the publisher value
     * @param relay
     *            the relay value
     * @param telemetry
     *            the telemetry value
     */
    public BusTaskProjectionCoordinator(BusResponseProjectionStore store, RuntimeBusResponsePublisher publisher,
            BusResponseRelay relay, BusConsumerTelemetry telemetry) {
        this(store, publisher, relay, telemetry, new BusConcurrencyGuard(16, 16, 16, 64));
    }

    /**
     * Creates a new instance.
     *
     * @param store
     *            the store value
     * @param publisher
     *            the publisher value
     * @param relay
     *            the relay value
     * @param telemetry
     *            the telemetry value
     * @param concurrency
     *            the concurrency value
     */
    public BusTaskProjectionCoordinator(BusResponseProjectionStore store, RuntimeBusResponsePublisher publisher,
            BusResponseRelay relay, BusConsumerTelemetry telemetry, BusConcurrencyGuard concurrency) {
        this.store = store;
        this.publisher = publisher;
        this.relay = relay;
        this.telemetry = telemetry;
        this.concurrency = concurrency;
    }

    /**
     * Performs the project operation.
     *
     * @param projection
     *            the projection value
     *
     * @return the operation result
     */
    public boolean project(BusResponseProjection projection) {
        if (store.isPublished(projection.tenantId(), projection.eventId())) {
            return false;
        }
        if (relay != null) {
            boolean submitted = relay.submit(projection);
            if (submitted) {
                audit(projection);
            }
            return submitted;
        }
        boolean appended = telemetry.observe("projection.append", family(projection.eventType()),
                () -> store.append(projection));
        if (!appended) {
            return false;
        }
        telemetry.projectionAppended();
        telemetry.observe("projection.publish", family(projection.eventType()), () -> {
            concurrency.call(BusConcurrencyGuard.Lane.PROJECTION, () -> {
                publisher.publish(projection);
                return null;
            });
            return null;
        });
        store.markPublished(projection.tenantId(), projection.eventId());
        telemetry.projectionPublished();
        telemetry.projection(projection.eventType(), "published");
        telemetry.projectionLag(projection.eventType(),
                java.time.Duration.between(projection.occurredAt(), java.time.Instant.now()).toNanos());
        audit(projection);
        return true;
    }

    private static void audit(BusResponseProjection projection) {
        String kind = projection.projectionKind();
        if (!"ACCEPTED".equals(kind) && !"INPUT_REQUIRED".equals(kind) && !"STREAM_READY".equals(kind)
                && !"TERMINAL".equals(kind)) {
            return;
        }
        LOG.info("bus projection audit tenantHash={} messageId={} correlationId={} taskId={} eventType={} kind={}",
                Integer.toHexString(projection.tenantId().hashCode()), projection.causationMessageId(),
                projection.correlationId(), projection.taskId(), projection.eventType(), kind);
    }

    private static String family(String type) {
        return type != null && type.startsWith("A2A") ? "a2a" : "invocation";
    }
}
