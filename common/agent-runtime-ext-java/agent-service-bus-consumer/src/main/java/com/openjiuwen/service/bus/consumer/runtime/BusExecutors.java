/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates bounded, named executors used by the bus consumer lifecycle.
 *
 * @since 2026-07-22
 */
public final class BusExecutors {
    private static final Logger LOG = LoggerFactory.getLogger(BusExecutors.class);

    private BusExecutors() {
    }

    /**
     * Creates a single-thread scheduled executor that logs otherwise invisible task failures.
     *
     * <p>A scheduled task that throws does not kill its worker thread: {@code FutureTask} stores the
     * throwable in the task's {@link Future} and the pool moves on, so neither the thread's
     * {@code UncaughtExceptionHandler} nor any log records it. The
     * {@link ScheduledThreadPoolExecutor#afterExecute} hook below drains that {@link Future} to make
     * such failures visible.
     *
     * <p>Note that this only restores observability. Callers that schedule repeating work must also
     * guarantee that a failed round cannot break the chain — see the {@code finally} based
     * re-arming in {@code BrokerConsumerLifecycle} and {@code BusProjectionRepairScheduler}.
     *
     * @param threadName worker thread name
     *
     * @return configured executor
     */
    public static ScheduledExecutorService singleThreadScheduler(String threadName) {
        ScheduledThreadPoolExecutor scheduler = new FailureLoggingScheduler(threadName);
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    /** Single-thread scheduler that reports task failures swallowed by {@link Future}. */
    private static final class FailureLoggingScheduler extends ScheduledThreadPoolExecutor {
        private final String threadName;

        private FailureLoggingScheduler(String threadName) {
            super(1, daemonThreadFactory(threadName));
            this.threadName = threadName;
        }

        @Override
        protected void afterExecute(Runnable task, Throwable thrown) {
            super.afterExecute(task, thrown);
            Optional<Throwable> failure = thrown == null
                    ? completedTaskFailure(task)
                    : Optional.of(thrown);
            failure.ifPresent(cause -> LOG.error("Uncaught failure in {}", threadName, cause));
        }

        /**
         * Extracts the throwable a completed task stored in its {@link Future}.
         *
         * @param task task the pool has just finished running
         *
         * @return the failure that ended the task, empty when it succeeded or was cancelled
         */
        private static Optional<Throwable> completedTaskFailure(Runnable task) {
            if (!(task instanceof Future<?> future) || !future.isDone()) {
                return Optional.empty();
            }
            try {
                future.get();
                return Optional.empty();
            } catch (CancellationException cancelled) {
                return Optional.empty();
            } catch (ExecutionException failure) {
                return Optional.ofNullable(failure.getCause());
            } catch (InterruptedException interrupted) {
                // Unreachable in practice: isDone() is checked above, so get() reports the stored
                // outcome without ever blocking. Report it as the failure rather than re-interrupting
                // the pool worker, which would cancel unrelated scheduled work on this thread.
                return Optional.of(interrupted);
            }
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger index = new AtomicInteger();
        ThreadFactory delegate = Executors.defaultThreadFactory();
        return runnable -> {
            Thread thread = delegate.newThread(runnable);
            thread.setName(prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
