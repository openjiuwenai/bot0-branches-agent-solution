/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步批量刷写器：Redis 写经内存队列 + 单后台守护线程按间隔批量刷写。背压语义：
 * 队列满则丢弃并 WARN（限速计数），绝不阻塞执行线程；关闭状态下无队列、无线程。
 *
 * <p>单个写任务失败不拖垮刷写线程——按任务隔离（数据访问异常与状态异常记录后继续），
 * 与"任何环节失败只 WARN 不影响 Agent 执行"的故障隔离纪律一致。
 *
 * @since 2026-08-26
 */
public class AsyncTrajectoryWriter implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncTrajectoryWriter.class);

    private static final long DROP_LOG_EVERY = 1000L;
    private static final long RESTART_BACKOFF_MS = 10_000L;

    private final LinkedBlockingQueue<Runnable> queue;
    private final int flushIntervalMs;
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong lastRestartMs = new AtomicLong();
    private volatile ThreadPoolExecutor executor;
    private volatile boolean running;
    private volatile boolean closed;

    /**
     * Creates the writer (not started until {@link #start()}).
     *
     * @param queueCapacity   backpressure queue capacity
     * @param flushIntervalMs batch flush interval in milliseconds
     */
    public AsyncTrajectoryWriter(int queueCapacity, int flushIntervalMs) {
        this.queue = new LinkedBlockingQueue<>(queueCapacity);
        this.flushIntervalMs = flushIntervalMs;
    }

    /**
     * Starts the single background flush thread (idempotent).
     */
    public synchronized void start() {
        if (running || closed) {
            return;
        }
        running = true;
        // 线程经池化管控（G.CON.12）：单线程池承载刷写循环；守护线程工厂
        executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, "trajectory-link-writer");
            thread.setDaemon(true);
            // 线程级兜底：未捕获的运行时异常致刷写线程死亡时退避后重启（防风暴）
            thread.setUncaughtExceptionHandler((dead, error) -> {
                long now = System.currentTimeMillis();
                if (now - lastRestartMs.get() < RESTART_BACKOFF_MS) {
                    LOGGER.error("trajectory writer thread died again within backoff window, stays dead: {}",
                            error.getMessage());
                    return;
                }
                lastRestartMs.set(now);
                LOGGER.error("trajectory writer thread died, restarting: {}", error.getMessage());
                running = false;
                start();
            });
            return thread;
        });
        executor.execute(this::flushLoop);
    }

    /**
     * Enqueues a write task. When the queue is full the task is dropped and a
     * rate-limited WARN is emitted; the calling (execution) thread never blocks.
     *
     * @param task write task
     */
    public void submit(Runnable task) {
        if (closed || !queue.offer(task)) {
            long dropped = droppedCount.incrementAndGet();
            if (dropped % DROP_LOG_EVERY == 1L) {
                LOGGER.warn("trajectory write task dropped ({}; total dropped={})",
                        closed ? "writer closed" : "queue full", dropped);
            }
        }
    }

    /**
     * Returns the number of dropped tasks (backpressure).
     *
     * @return dropped task count
     */
    public long getDroppedCount() {
        return droppedCount.get();
    }

    /**
     * Returns the number of failed tasks (flushed but errored).
     *
     * @return failed task count
     */
    public long getFailedCount() {
        return failedCount.get();
    }

    /**
     * Stops the thread and drains the remaining queue best-effort.
     */
    @Override
    public synchronized void close() {
        closed = true;
        running = false;
        if (executor != null) {
            // 协作式停止：不中断线程，刷写循环经 running=false 与 poll 超时自然退出
            executor.shutdown();
            boolean ignored = awaitTerminationQuietly();
            executor = null;
        }
        drainOnce();
    }

    private void flushLoop() {
        while (running || !queue.isEmpty()) {
            try {
                Runnable first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<Runnable> batch = new ArrayList<>();
                batch.add(first);
                queue.drainTo(batch);
                runBatch(batch);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean awaitTerminationQuietly() {
        try {
            return executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    private void runBatch(List<Runnable> batch) {
        for (Runnable task : batch) {
            // 按任务隔离：本代码可预见的具体异常集吞掉并继续；Redis 客户端实现的私有
            // 运行时异常不在集内，上抛由线程级退避重启兜底（批内后续任务随之丢弃，有界损失）
            try {
                task.run();
            } catch (IllegalStateException | IllegalArgumentException | ClassCastException e) {
                long failed = failedCount.incrementAndGet();
                if (failed % DROP_LOG_EVERY == 1L) {
                    LOGGER.warn("trajectory write task failed (total failed={}): {}", failed, e.getMessage());
                }
            }
        }
    }

    private void drainOnce() {
        List<Runnable> rest = new ArrayList<>();
        queue.drainTo(rest);
        runBatch(rest);
    }
}
