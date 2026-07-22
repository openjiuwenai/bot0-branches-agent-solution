/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.relay;

import com.openjiuwen.bus.forwarding.common.AgentBusBrokerProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Schedules the two-hop relay ticks on a dedicated {@link ThreadPoolTaskScheduler}
 * (relay-scheduler slice; spec {@code docs/superpowers/specs/2026-07-15-relay-scheduler-design.md}).
 *
 * <p><b>Dedicated pool, not the global @Scheduled pool:</b> {@code RegistrySchedulingConfig}
 * binds the global {@code @Scheduled} {@code TaskScheduler} to the registry probes via
 * {@code setTaskScheduler}; a {@code @Scheduled} relay method would land there (shared with
 * probes). The relay is instead driven <b>programmatically</b> ({@code scheduleWithFixedDelay})
 * on its own pool — a hung relay tick blocks only its own thread.
 *
 * @since 0.1.0
 */
@Configuration
@Profile("eventbus")
public class EventBusRelaySchedulingConfig {
    /**
     * Build the dedicated relay-scheduler slice (pool size 2, {@code relay-} thread prefix)
     * that drives the two-hop relay ticks programmatically.
     *
     * @return a {@link ThreadPoolTaskScheduler} configured for the relay slice
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler relayTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("relay-");
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    /**
     * Build the {@link RelayScheduler} that drives the forward and response relay ticks
     * on the dedicated {@code relayTaskScheduler} slice.
     *
     * @param forward             the forward-hop {@link RelayTick}
     * @param response           the response-hop {@link RelayTick}
     * @param props              the broker/relay properties (tenant, tick limit, cadence)
     * @param relayTaskScheduler the dedicated relay-scheduler slice
     * @return a configured {@link RelayScheduler} bound to the relay slice
     */
    @Bean
    public RelayScheduler relayScheduler(
            @Qualifier("forwardRelayTick") RelayTick forward,
            @Qualifier("responseRelayTick") RelayTick response,
            AgentBusBrokerProperties props,
            ThreadPoolTaskScheduler relayTaskScheduler) {
        return new RelayScheduler(forward, response, props.tenant(),
                props.relayTickLimit(), props.relayFixedDelayMs(), relayTaskScheduler);
    }
}
