/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ScheduledExecutorService;

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
     * Creates a single-thread scheduled executor with explicit shutdown and failure policies.
     *
     * @param threadName
     *            worker thread name
     *
     * @return configured executor
     */
    public static ScheduledExecutorService singleThreadScheduler(String threadName) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(threadName + "-");
        scheduler.setDaemon(true);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(failure -> LOG.error("Uncaught failure in {}", threadName, failure));
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler.getScheduledExecutor();
    }
}
