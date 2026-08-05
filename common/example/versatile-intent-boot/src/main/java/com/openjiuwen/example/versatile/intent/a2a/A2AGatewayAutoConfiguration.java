/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that activates the A2A Gateway Caller/Resolver beans when
 * {@code openjiuwen.service.a2a-gateway.enabled=true}.
 *
 * <p>When the property is {@code false} or absent, the runtime core module's
 * {@code DefaultRemoteAgentCaller} / {@code DefaultCardResolver} beans remain
 * active (wired via {@code @ConditionalOnMissingBean} in {@code A2AAutoConfiguration}).
 *
 * <p>Declared to load <em>before</em> {@link A2AAutoConfiguration} so the gateway
 * beans register first and the {@code @ConditionalOnMissingBean} guards on the
 * {@code Default*} beans skip cleanly. Without this explicit ordering the
 * override would depend on Spring Boot's tie-breaking sorter, which is fragile
 * against classpath or version changes.
 *
 * @since 0.1.0
 */
@AutoConfiguration(before = A2AAutoConfiguration.class)
@EnableConfigurationProperties(A2AGatewayProperties.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.a2a-gateway", name = "enabled", havingValue = "true")
public class A2AGatewayAutoConfiguration {
    /**
     * Registers the gateway card resolver bean.
     *
     * @param properties the gateway properties
     * @return the gateway card resolver
     */
    @Bean
    @ConditionalOnMissingBean(RemoteAgentCardResolver.class)
    public A2AGatewayCardResolver a2aGatewayCardResolver(A2AGatewayProperties properties) {
        return new A2AGatewayCardResolver(properties);
    }

    /**
     * Registers the gateway caller bean.
     *
     * @param properties    the gateway properties
     * @param cardResolver  the gateway card resolver
     * @return the gateway caller
     */
    @Bean
    @ConditionalOnMissingBean(RemoteAgentCaller.class)
    public A2AGatewayRemoteAgentCaller a2aGatewayRemoteAgentCaller(
            A2AGatewayProperties properties, RemoteAgentCardResolver cardResolver) {
        return new A2AGatewayRemoteAgentCaller(properties, cardResolver);
    }
}
