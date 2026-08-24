/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.runtime.BusExecutors;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Tests that the projection repair chain survives failures the scheduler cannot name.
 *
 * @since 2026-08-23
 */
class BusProjectionRepairSchedulerTest {
    @Test
    void keepsRepairingAfterAnUnexpectedFailure() throws InterruptedException {
        var store = new InMemoryBusResponseProjectionStore();
        store.append(new BusResponseProjection("e", "INVOCATION_RESPONSE", "t", "c", "task", Instant.now(), Map.of()));
        CountDownLatch threeRounds = new CountDownLatch(3);
        ScheduledExecutorService relayScheduler = BusExecutors.singleThreadScheduler("bus-repair-chain-test");
        // Not an IllegalArgumentException/IllegalStateException on purpose: the relay only retries the
        // failures it can name, so this one escapes repair() and would have cancelled a
        // scheduleWithFixedDelay chain outright. The projection is never marked published, so every
        // round finds it pending again.
        try (var relay = new BusResponseRelay(store, projection -> {
            threeRounds.countDown();
            throw new PublisherBlewUp();
        }, relayScheduler, 1, Duration.ofMillis(1))) {
            var scheduler = new BusProjectionRepairScheduler(new BusProjectionRepairer(store, relay, "t"), 1);
            scheduler.start();
            try {
                assertThat(threeRounds.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(store.isPublished("t", "e")).isFalse();
            } finally {
                scheduler.stop();
            }
        } finally {
            relayScheduler.shutdownNow();
        }
    }

    /** Stands in for the storage or broker failures a publisher can raise. */
    private static final class PublisherBlewUp extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private PublisherBlewUp() {
            super("publisher blew up");
        }
    }
}
