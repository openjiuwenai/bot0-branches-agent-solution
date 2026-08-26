/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * AsyncTrajectoryWriter 的单元测试：批量刷写、背压丢弃、任务失败隔离、关闭排干。
 */
class AsyncTrajectoryWriterTest {
    @Test
    void flushesSubmittedTasksInBackground() {
        AtomicInteger executed = new AtomicInteger();
        try (AsyncTrajectoryWriter writer = new AsyncTrajectoryWriter(100, 20)) {
            writer.start();
            for (int i = 0; i < 5; i++) {
                writer.submit(executed::incrementAndGet);
            }
            waitUntil(() -> executed.get() == 5);
            assertThat(writer.getDroppedCount()).isZero();
        }
    }

    @Test
    void dropsWhenQueueFullWithoutBlockingCaller() {
        try (AsyncTrajectoryWriter writer = new AsyncTrajectoryWriter(2, 60_000)) {
            // 未 start 时允许入队（有界），塞满后的第 3 个即丢弃
            writer.submit(() -> { });
            writer.submit(() -> { });
            long start = System.nanoTime();
            writer.submit(() -> { });
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(1000);
            assertThat(writer.getDroppedCount()).isEqualTo(1);
        }
    }

    @Test
    void taskFailureIsCountedAndBatchContinues() {
        AtomicInteger executed = new AtomicInteger();
        try (AsyncTrajectoryWriter writer = new AsyncTrajectoryWriter(100, 20)) {
            writer.start();
            writer.submit(() -> {
                throw new IllegalStateException("redis down");
            });
            writer.submit(executed::incrementAndGet);
            waitUntil(() -> executed.get() == 1);
            waitUntil(() -> writer.getFailedCount() == 1);
        }
    }

    @Test
    void closedWriterCannotRestart() {
        AsyncTrajectoryWriter writer = new AsyncTrajectoryWriter(100, 20);
        writer.close();
        writer.start();
        writer.submit(() -> { });
        assertThat(writer.getDroppedCount()).isEqualTo(1);
    }

    @Test
    void closeDrainsRemainingQueue() throws Exception {
        AsyncTrajectoryWriter writer = new AsyncTrajectoryWriter(100, 60_000);
        AtomicInteger executed = new AtomicInteger();
        writer.submit(executed::incrementAndGet);
        writer.submit(executed::incrementAndGet);
        writer.close();
        assertThat(executed.get()).isEqualTo(2);
    }

    @Test
    void queuedBeforeStartIsFlushedAfterStart() {
        AtomicInteger executed = new AtomicInteger();
        try (AsyncTrajectoryWriter writer = new AsyncTrajectoryWriter(100, 20)) {
            writer.submit(executed::incrementAndGet);
            writer.start();
            waitUntil(() -> executed.get() == 1);
        }
    }

    @Test
    void submitAfterCloseIsDropped() {
        AsyncTrajectoryWriter writer = new AsyncTrajectoryWriter(100, 20);
        writer.close();
        writer.submit(() -> { });
        assertThat(writer.getDroppedCount()).isEqualTo(1);
    }

    private static void waitUntil(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("condition not met within 3s");
    }
}
