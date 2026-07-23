/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent;

import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.orchestrator.ServeForwardStrategy;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers {@link ThreeFieldForwardStrategy} as the
 * active {@link ServeForwardStrategy} for Versatile intent-workflow deployments.
 *
 * <p>Declared to load <em>before</em> {@link A2AAutoConfiguration} so the
 * three-field strategy registers first and the runtime core's
 * {@code NoopServeForwardStrategy} bean (guarded by
 * {@code @ConditionalOnMissingBean(ServeForwardStrategy.class)}) skips cleanly.
 *
 * <p>Conditional on {@code openjiuwen.service.versatile.url-template} so the
 * strategy is only active when the deployment actually points at a Versatile
 * endpoint — matching {@link VersatileIntentAutoConfiguration}'s activation
 * condition.
 *
 * @since 0.1.0
 */
@AutoConfiguration(before = A2AAutoConfiguration.class)
@ConditionalOnClass(VersatileAgentHandler.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.versatile", name = "url-template")
public class ThreeFieldForwardStrategyAutoConfiguration {

    /**
     * Registers the three-field forward strategy bean.
     *
     * @return the three-field forward strategy
     */
    @Bean
    @ConditionalOnMissingBean(ServeForwardStrategy.class)
    public ThreeFieldForwardStrategy threeFieldForwardStrategy() {
        return new ThreeFieldForwardStrategy();
    }
}
