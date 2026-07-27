/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.example.versatile.intent.routecache.RouteCache;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.Objects;
import java.util.Optional;

/**
 * Replaces the runtime's {@link A2AEnabledServeOrchestrator} bean with a
 * {@link ReclassifyServeOrchestrator} wrapper after initialization. Other
 * beans that inject {@code ServeOrchestrator} receive the decorator without
 * any change to their own wiring.
 *
 * <p>The post-processor matches on the concrete
 * {@code A2AEnabledServeOrchestrator} type (not the {@code ServeOrchestrator}
 * interface) so it does not accidentally wrap itself or other custom
 * orchestrators.
 *
 * <p>An {@link ObjectProvider} for {@link RouteCache} is used so the
 * decorator can be created even when route-cache support is disabled at
 * runtime (no {@code RouteCache} bean in the context). The wrapped
 * decorator receives {@link Optional#empty()} in that case.
 *
 * @since 2026-07-24
 */
public class ReclassifyOrchestratorPostProcessor implements BeanPostProcessor {
    private final ReclassifyProperties properties;
    private final ObjectProvider<RouteCache> routeCacheProvider;

    public ReclassifyOrchestratorPostProcessor(ReclassifyProperties properties,
                                                ObjectProvider<RouteCache> routeCacheProvider) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.routeCacheProvider = Objects.requireNonNull(routeCacheProvider, "routeCacheProvider");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof A2AEnabledServeOrchestrator wrapped) {
            RouteCache cache = routeCacheProvider.getIfAvailable();
            return new ReclassifyServeOrchestrator(wrapped, properties,
                    cache == null ? Optional.empty() : Optional.of(cache));
        }
        return bean;
    }
}
