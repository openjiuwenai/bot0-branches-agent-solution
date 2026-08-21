/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

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
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
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
                    Thread.currentThread().interrupt();
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
    void release_belowZeroGuard_isClamped() {
        TaskAdmissionControl gate = new TaskAdmissionControl(1);
        gate.tryAcquire();
        gate.release();
        gate.release();
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
