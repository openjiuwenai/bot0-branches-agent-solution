/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.example.versatile.intent.reclassify;

import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import java.util.Objects;

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
 * @since 2026-07-24
 */
public class ReclassifyOrchestratorPostProcessor implements BeanPostProcessor {
    private final ReclassifyProperties properties;

    public ReclassifyOrchestratorPostProcessor(ReclassifyProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof A2AEnabledServeOrchestrator wrapped) {
            return new ReclassifyServeOrchestrator(wrapped, properties);
        }
        return bean;
    }
}
