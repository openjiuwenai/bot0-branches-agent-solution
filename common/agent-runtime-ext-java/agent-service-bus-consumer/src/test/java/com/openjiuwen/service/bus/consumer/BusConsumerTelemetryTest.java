/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.observability.BusConsumerTelemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.junit.jupiter.api.Test;

/**
 * Tests BusConsumerTelemetry behavior.
 *
 * @since 2026-07-22
 */
class BusConsumerTelemetryTest {
    @Test
    void recordsRequiredLowCardinalityMetrics() {
        var meters = new SimpleMeterRegistry();
        var telemetry = new BusConsumerTelemetry(meters, ObservationRegistry.create());
        telemetry.observe("bus.consume", "CLIENT", () -> "ok");
        telemetry.delivery("CLIENT", "ACK_CONSUMED", 1_000);
        telemetry.admission("CREATED");
        telemetry.projection("INVOCATION_ACCEPTED", "published");
        telemetry.projectionLag("INVOCATION_ACCEPTED", 1_000);
        telemetry.payload("inline", "success", 1_000);
        telemetry.repair("projection", "scheduled");
        telemetry.projectionAppended();

        assertThat(meters.find("runtime_bus_deliveries_total").counter()).isNotNull();
        assertThat(meters.find("runtime_bus_delivery_latency_seconds").timer()).isNotNull();
        assertThat(meters.find("runtime_bus_admission_total").counter()).isNotNull();
        assertThat(meters.find("runtime_bus_projection_pending").gauge().value()).isEqualTo(1);
        assertThat(meters.find("runtime_bus_projection_publish_total").counter()).isNotNull();
        assertThat(meters.find("runtime_bus_projection_lag_seconds").timer()).isNotNull();
        assertThat(meters.find("runtime_bus_payload_resolve_seconds").timer()).isNotNull();
        assertThat(meters.find("runtime_bus_repair_total").counter()).isNotNull();
    }
}
