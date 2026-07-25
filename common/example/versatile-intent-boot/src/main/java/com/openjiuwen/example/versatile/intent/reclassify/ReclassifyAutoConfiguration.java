/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.example.versatile.intent.routecache.RouteCache;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the {@link ReclassifyOrchestratorPostProcessor} when
 * {@code openjiuwen.service.versatile.intent-reclassify.enabled=true}. The
 * post-processor wraps the runtime's {@code A2AEnabledServeOrchestrator} bean
 * with a {@link ReclassifyServeOrchestrator} decorator.
 *
 * <p>When the property is {@code false} (default), no post-processor is
 * registered and the original orchestrator bean is used unchanged.
 *
 * @since 2026-07-24
 */
@AutoConfiguration
@EnableConfigurationProperties(ReclassifyProperties.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.versatile.intent-reclassify", name = "enabled",
        havingValue = "true")
public class ReclassifyAutoConfiguration {
    /**
     * Registers the orchestrator post-processor bean.
     *
     * @param properties the reclassify properties
     * @param routeCacheProvider optional provider for the L1 {@link RouteCache};
     *                           absent when route-cache support is disabled
     * @return the post-processor
     */
    @Bean
    public static ReclassifyOrchestratorPostProcessor reclassifyOrchestratorPostProcessor(
            ReclassifyProperties properties, ObjectProvider<RouteCache> routeCacheProvider) {
        return new ReclassifyOrchestratorPostProcessor(properties, routeCacheProvider);
    }
}
