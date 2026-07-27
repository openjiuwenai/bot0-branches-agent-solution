/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.relay.BusProjectionRepairer;
import com.openjiuwen.service.bus.consumer.relay.BusResponseRelay;
import com.openjiuwen.service.bus.consumer.runtime.BusExecutors;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Tests replay of persisted projections that were not marked as published.
 *
 * @since 2026-07-22
 */
class BusProjectionRepairerTest {
    @Test
    void repairsPersistedUnpublishedProjection() {
        var store = new InMemoryBusResponseProjectionStore();
        var projection = new BusResponseProjection("e", "INVOCATION_RESPONSE", "t", "c", "task", Instant.now(),
                Map.of());
        store.append(projection);
        var scheduler = BusExecutors.singleThreadScheduler("bus-projection-repair-test");
        try (var relay = new BusResponseRelay(store, p -> {
        }, scheduler, 1, Duration.ofMillis(1))) {
            assertThat(new BusProjectionRepairer(store, relay, "t").repair(10)).isEqualTo(1);
            assertThat(store.isPublished("t", "e")).isTrue();
        }
    }
}
