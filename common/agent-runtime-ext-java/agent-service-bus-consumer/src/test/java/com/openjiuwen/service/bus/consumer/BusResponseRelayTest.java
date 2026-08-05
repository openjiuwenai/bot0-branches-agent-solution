/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.relay.BusResponseRelay;
import com.openjiuwen.service.bus.consumer.runtime.BusExecutors;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests bounded response relay retries and publication marking.
 *
 * @since 2026-07-22
 */
class BusResponseRelayTest {
    @Test
    void retriesThenMarksPublished() throws InterruptedException {
        var store = new InMemoryBusResponseProjectionStore();
        var attempts = new AtomicInteger();
        var scheduler = BusExecutors.singleThreadScheduler("bus-response-relay-test");
        try (var relay = new BusResponseRelay(store, p -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("down");
            }
        }, scheduler, 3, Duration.ofMillis(5))) {
            var p = new BusResponseProjection("e", "INVOCATION_RESPONSE", "t", "c", "task", Instant.now(), Map.of());
            relay.submit(p);
            await().until(() -> store.isPublished("t", "e"));
            assertThat(attempts).hasValue(3);
        }
    }

    private static Await await() {
        return new Await();
    }

    private static final class Await {
        void until(java.util.function.BooleanSupplier condition) throws InterruptedException {
            for (int i = 0; i < 100 && !condition.getAsBoolean(); i++) {
                Thread.sleep(10);
            }
            assertThat(condition.getAsBoolean()).isTrue();
        }
    }
}
