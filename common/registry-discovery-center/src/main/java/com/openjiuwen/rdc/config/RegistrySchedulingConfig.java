/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * Enables {@code @Scheduled} support for the agent registry MVP health-probe
 * scheduler (ADR-0160) and binds the probe to a dedicated
 * {@link ThreadPoolTaskScheduler} so a hung probe cannot stall the default
 * single-threaded scheduler that other Spring beans rely on (PR #389 review
 * issue #2).
 *
 * <p>{@code agent-bus} is a runnable Spring Boot application
 * ({@link com.openjiuwen.rdc.AgentRdcApplication}, PR #389 review issue #6);
 * this standalone {@code @Configuration} class is picked up by the application's
 * component scan, which covers {@code com.openjiuwen.rdc..}. Once phase 2
 * retires {@code MvpHealthProbeScheduler}, this class can be deleted in lockstep.
 *
 * <p>Authority: ADR-0160 + PR #389 review issue #6 (agent-bus is a runnable
 * Spring Boot application) + PR #389 review issue #2 (probe scheduler thread
 * isolation).
 *
 * @since 0.1.0 (2026)
 */
@Configuration
@EnableScheduling
public class RegistrySchedulingConfig implements SchedulingConfigurer {
    /**
     * Dedicated {@link ThreadPoolTaskScheduler} for registry probe work.
     * Pool size 2 is enough for the MVP single-tenant sweep; the scheduler
     * processes targets sequentially within one sweep, so the second thread
     * is purely a safety margin for slow probes that overlap into the next
     * fixed-delay window. Hung probes are bounded by the
     * {@code MvpHealthProbeScheduler}'s RestClient read timeout (default 2s)
     * — they cannot pin a thread indefinitely.
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler registryProbeTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("registry-probe-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /**
     * configureTasks.
     *
     * @param registrar registrar
     * @since 0.1.0
     */
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        // Bind the registry probe's @Scheduled methods to the dedicated
        // thread pool above — without this, Spring falls back to the
        // default single-thread TaskScheduler, where one hung probe stalls
        // every other @Scheduled bean in the application context.
        registrar.setTaskScheduler(registryProbeTaskScheduler());
    }
}
