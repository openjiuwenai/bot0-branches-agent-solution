/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link TaskAdmissionControl}.
 *
 * @since 0.1.0
 */
class TaskAdmissionControlTest {
    @Test
    void tryAcquire_returnsTrue_whenUnderLimit() {
        TaskAdmissionControl gate = new TaskAdmissionControl(3);
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.currentCount()).isEqualTo(1);
    }

    @Test
    void tryAcquire_returnsFalse_whenAtLimit() {
        TaskAdmissionControl gate = new TaskAdmissionControl(2);
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.tryAcquire()).isFalse();
        assertThat(gate.currentCount()).isEqualTo(2);
    }

    @Test
    void release_decrementsCount() {
        TaskAdmissionControl gate = new TaskAdmissionControl(3);
        gate.tryAcquire();
        gate.tryAcquire();
        gate.release();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.currentCount()).isEqualTo(2);
    }

    @Test
    void tryAcquire_alwaysTrue_whenUnlimited() {
        TaskAdmissionControl gate = new TaskAdmissionControl(-1);
        for (int i = 0; i < 100; i++) {
            assertThat(gate.tryAcquire()).isTrue();
        }
        assertThat(gate.currentCount()).isZero();
    }

    @Test
    void release_noop_whenUnlimited() {
        TaskAdmissionControl gate = new TaskAdmissionControl(-1);
        gate.tryAcquire();
        gate.release();
        assertThat(gate.currentCount()).isZero();
    }

    @Test
    void tryAcquire_concurrent_doesNotExceedLimit() throws InterruptedException {
        TaskAdmissionControl gate = new TaskAdmissionControl(10);
        int threadCount = 50;
        ExecutorService pool = new ThreadPoolExecutor(
                threadCount, threadCount, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    if (gate.tryAcquire()) {
                        successCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    // The latch is counted down promptly and the pool is shut
                    // down gracefully, so workers are never interrupted here;
                    // a failed wait simply counts as a failed acquisition.
                } finally {
                    doneLatch.countDown();
                }
            });
        }
        startLatch.countDown();
        assertThat(doneLatch.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(successCount.get()).isEqualTo(10);
        assertThat(gate.currentCount()).isEqualTo(10);
    }

    @Test
    void release_withoutAcquire_failsLoudly() {
        TaskAdmissionControl gate = new TaskAdmissionControl(1);
        assertThatThrownBy(gate::release)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("double-release");
        assertThat(gate.currentCount()).isZero();
    }

    @Test
    void release_doubleRelease_failsLoudly_andGateRemainsUsable() {
        TaskAdmissionControl gate = new TaskAdmissionControl(1);
        assertThat(gate.tryAcquire()).isTrue();
        gate.release();
        assertThatThrownBy(gate::release)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current count already at 0");
        assertThat(gate.currentCount()).isZero();
        assertThat(gate.tryAcquire()).isTrue();
        gate.release();
        assertThat(gate.currentCount()).isZero();
    }

    @Test
    void release_concurrent_exactlyOneSucceeds() throws InterruptedException {
        TaskAdmissionControl gate = new TaskAdmissionControl(2);
        assertThat(gate.tryAcquire()).isTrue();
        AtomicInteger failures = new AtomicInteger();
        Runnable releaseTask = () -> {
            try {
                gate.release();
            } catch (IllegalStateException e) {
                failures.incrementAndGet();
            }
        };
        ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        try {
            pool.execute(releaseTask);
            pool.execute(releaseTask);
        } finally {
            pool.shutdown();
        }
        assertThat(pool.awaitTermination(10L, TimeUnit.SECONDS)).isTrue();
        assertThat(failures.get()).isEqualTo(1);
        assertThat(gate.currentCount()).isZero();
    }

    @Test
    void shutdown_rejectsAllNewRequests() {
        TaskAdmissionControl gate = new TaskAdmissionControl(3);
        gate.shutdown();
        assertThat(gate.tryAcquire()).isFalse();
    }

    @Test
    void reset_clearsShutdownAndCount() {
        TaskAdmissionControl gate = new TaskAdmissionControl(3);
        gate.tryAcquire();
        gate.tryAcquire();
        gate.shutdown();
        gate.reset();
        assertThat(gate.tryAcquire()).isTrue();
        assertThat(gate.currentCount()).isEqualTo(1);
    }

    @Test
    void currentCount_reflectsActualState() {
        TaskAdmissionControl gate = new TaskAdmissionControl(3);
        gate.tryAcquire();
        gate.tryAcquire();
        gate.release();
        assertThat(gate.currentCount()).isEqualTo(1);
    }

    @Test
    void limit_returnsConfiguredMax() {
        assertThat(new TaskAdmissionControl(7).limit()).isEqualTo(7);
    }

    @Test
    void limit_returnsMinusOne_whenUnlimited() {
        assertThat(new TaskAdmissionControl(-1).limit()).isEqualTo(-1);
    }
}
