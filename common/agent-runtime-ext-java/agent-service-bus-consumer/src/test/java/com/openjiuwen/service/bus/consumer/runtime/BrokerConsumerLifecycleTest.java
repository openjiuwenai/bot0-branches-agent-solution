/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.model.BusConsumptionDecision;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests that the broker tick chain survives failures the lifecycle cannot name.
 *
 * @since 2026-08-23
 */
class BrokerConsumerLifecycleTest {
    @Test
    void keepsTickingAfterAnUnexpectedFailure() throws InterruptedException {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch fourTicks = new CountDownLatch(4);
        BrokerDeliveryLoop loop = loop(() -> {
            fourTicks.countDown();
            if (ticks.incrementAndGet() <= 2) {
                // Not an IllegalArgumentException/IllegalStateException on purpose: the point of the
                // finally-based re-arming is that even a throwable the tick body does not catch leaves
                // the chain alive.
                throw new BrokerBlewUp();
            }
            return Optional.empty();
        });
        BrokerConsumerLifecycle lifecycle = new BrokerConsumerLifecycle(loop, 1);

        lifecycle.start();
        try {
            assertThat(fourTicks.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            lifecycle.stop();
        }
    }

    @Test
    void stopHaltsTheTickChain() throws InterruptedException {
        AtomicInteger ticks = new AtomicInteger();
        CountDownLatch threeTicks = new CountDownLatch(3);
        BrokerDeliveryLoop loop = loop(() -> {
            ticks.incrementAndGet();
            threeTicks.countDown();
            return Optional.empty();
        });
        BrokerConsumerLifecycle lifecycle = new BrokerConsumerLifecycle(loop, 1);

        lifecycle.start();
        assertThat(threeTicks.await(5, TimeUnit.SECONDS)).isTrue();
        lifecycle.stop();

        assertThat(lifecycle.isRunning()).isFalse();
        int afterStop = ticks.get();
        Thread.sleep(100);
        assertThat(ticks.get()).isEqualTo(afterStop);
    }

    private static BrokerDeliveryLoop loop(Poller poller) {
        return new BrokerDeliveryLoop(poller::poll, delivery -> {
        }, (delivery, reason) -> {
        }, delivery -> BusConsumptionDecision.consumed());
    }

    @FunctionalInterface
    private interface Poller {
        Optional<AgentBusBrokerDeliveryPort.Delivery> poll();
    }

    /** Stands in for the broker-specific failures this module cannot reference from a test. */
    private static final class BrokerBlewUp extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private BrokerBlewUp() {
            super("broker port blew up");
        }
    }
}
