/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Low-cardinality metrics and observation spans for the FEAT-017 control path.
 *
 * @since 2026-07-22
 */
public final class BusConsumerTelemetry {
    private final MeterRegistry meters;
    private final ObservationRegistry observations;
    private final AtomicInteger pending = new AtomicInteger();

    /**
     * Creates a new instance.
     *
     * @param meters
     *            the meters value
     * @param observations
     *            the observations value
     */
    public BusConsumerTelemetry(MeterRegistry meters, ObservationRegistry observations) {
        this.meters = meters;
        this.observations = observations == null ? ObservationRegistry.NOOP : observations;
        if (meters != null) {
            meters.gauge("runtime_bus_projection_pending", pending);
        }
    }

    /**
     * Performs the observe operation.
     *
     * @param name
     *            the name value
     * @param family
     *            the family value
     * @param action
     *            the action value
     *
     * @return the operation result
     *
     * @throws E
     *             if the operation cannot be completed
     */
    public <T, E extends Throwable> T observe(String name, String family, Observation.CheckedCallable<T, E> action)
            throws E {
        return Observation.createNotStarted(name, observations)
                .lowCardinalityKeyValue("event.family", family == null ? "unknown" : family).observeChecked(action);
    }

    /**
     * Performs the delivery operation.
     *
     * @param family
     *            the family value
     * @param result
     *            the result value
     * @param nanos
     *            the nanos value
     */
    public void delivery(String family, String result, long nanos) {
        if (meters == null) {
            return;
        }
        meters.counter("runtime_bus_deliveries_total", "event.family", family, "result", result).increment();
        Timer.builder("runtime_bus_delivery_latency_seconds").tag("event.family", family).register(meters).record(nanos,
                java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * Performs the admission operation.
     *
     * @param result
     *            the result value
     */
    public void admission(String result) {
        if (meters != null) {
            meters.counter("runtime_bus_admission_total", "result", result).increment();
        }
    }

    /**
     * Performs the projection operation.
     *
     * @param eventType
     *            the eventType value
     * @param result
     *            the result value
     */
    public void projection(String eventType, String result) {
        if (meters != null) {
            meters.counter("runtime_bus_projection_publish_total", "event.type", eventType, "result", result)
                    .increment();
        }
    }

    /**
     * Performs the projectionLag operation.
     *
     * @param eventType
     *            the eventType value
     * @param nanos
     *            the nanos value
     */
    public void projectionLag(String eventType, long nanos) {
        if (meters != null) {
            Timer.builder("runtime_bus_projection_lag_seconds").tag("event.type", eventType).register(meters)
                    .record(Math.max(0, nanos), java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    /**
     * Performs the payload operation.
     *
     * @param mode
     *            the mode value
     * @param result
     *            the result value
     * @param nanos
     *            the nanos value
     */
    public void payload(String mode, String result, long nanos) {
        if (meters == null) {
            return;
        }
        Timer.builder("runtime_bus_payload_resolve_seconds").tag("mode", mode).tag("result", result).register(meters)
                .record(nanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * Performs the repair operation.
     *
     * @param target
     *            the target value
     * @param result
     *            the result value
     */
    public void repair(String target, String result) {
        if (meters != null) {
            meters.counter("runtime_bus_repair_total", "target", target, "result", result).increment();
        }
    }

    /**
     * Performs the projectionAppended operation.
     */
    public void projectionAppended() {
        pending.incrementAndGet();
    }

    /**
     * Performs the projectionPublished operation.
     */
    public void projectionPublished() {
        pending.updateAndGet(value -> Math.max(0, value - 1));
    }
}
