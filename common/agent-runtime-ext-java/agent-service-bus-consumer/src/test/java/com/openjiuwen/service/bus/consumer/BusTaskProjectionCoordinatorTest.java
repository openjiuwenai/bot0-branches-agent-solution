/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests BusTaskProjectionCoordinator behavior.
 *
 * @since 2026-07-22
 */
class BusTaskProjectionCoordinatorTest {
    @Test
    void publishesEachEventIdOnlyOnce() {
        AtomicInteger published = new AtomicInteger();
        BusTaskProjectionCoordinator coordinator = new BusTaskProjectionCoordinator(
                new InMemoryBusResponseProjectionStore(), p -> published.incrementAndGet());
        BusResponseProjection projection = new BusResponseProjection("event-1", "INVOCATION_ACCEPTED", "tenant-a",
                "corr-1", "task-1", Instant.now(), Map.of());
        assertThat(coordinator.project(projection)).isTrue();
        assertThat(coordinator.project(projection)).isFalse();
        assertThat(published).hasValue(1);
    }
}
