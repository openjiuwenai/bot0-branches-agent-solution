/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;

import io.opentelemetry.api.trace.Tracer;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;

/**
 * Wraps every {@link RemoteAgentCaller} bean with {@link OtelRemoteAgentCallerDecorator}
 * so dispatch/versatile spans fire on the channel the batch coordinator actually uses
 * (precedent: {@code TaskStoreProjectionPostProcessor} wrapping TaskStore beans).
 *
 * @since 2026-08-17
 */
public class OtelRemoteAgentCallerPostProcessor implements BeanPostProcessor, Ordered {
    private final Tracer tracer;
    private final ObjectProvider<A2ARemoteAgentCardRegistry> registryProvider;

    /**
     * Creates the post processor.
     *
     * @param tracer           tracer for dispatch spans
     * @param registryProvider remote agent card registry provider (for sub_agent_url)
     */
    public OtelRemoteAgentCallerPostProcessor(Tracer tracer,
                                              ObjectProvider<A2ARemoteAgentCardRegistry> registryProvider) {
        this.tracer = tracer;
        this.registryProvider = registryProvider;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof RemoteAgentCaller caller && !(bean instanceof OtelRemoteAgentCallerDecorator)) {
            return new OtelRemoteAgentCallerDecorator(caller, tracer, registryProvider.getIfAvailable());
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
