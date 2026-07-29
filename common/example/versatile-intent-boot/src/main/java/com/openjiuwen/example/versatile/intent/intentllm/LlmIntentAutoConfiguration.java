/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import com.openjiuwen.example.versatile.intent.VersatileIntentAutoConfiguration;
import com.openjiuwen.example.versatile.intent.routecache.CachedVersatileAgentHandler;
import com.openjiuwen.example.versatile.intent.routecache.RouteCache;
import com.openjiuwen.example.versatile.intent.routecache.RouteCacheProperties;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers {@link LlmIntentAgentHandler} as the primary {@link AgentHandler}
 * when {@code intent-llm.enabled=true}. Ordered AFTER RouteCacheAutoConfiguration
 * (see imports) so the {@link RouteCache} bean is available for wrapping; when
 * route-cache is enabled, wraps the handler with {@link CachedVersatileAgentHandler}
 * so L1 keeps its route cache.
 *
 * @since 0.1.0
 */
@AutoConfiguration(before = VersatileIntentAutoConfiguration.class)
@ConditionalOnClass(AgentHandler.class)
@ConditionalOnProperty(prefix = "openjiuwen.example.intent-llm", name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(LlmIntentProperties.class)
public class LlmIntentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    public AgentHandler llmIntentAgentHandler(LlmIntentProperties properties,
                                               VersatileProperties versatileProperties,
                                               ObjectProvider<RouteCache> routeCache,
                                               ObjectProvider<RouteCacheProperties> routeCacheProperties) {
        properties.validate();
        LlmIntentAgentHandler base = new LlmIntentAgentHandler(properties, versatileProperties);
        RouteCache cache = routeCache.getIfAvailable();
        RouteCacheProperties rcProps = routeCacheProperties.getIfAvailable();
        if (cache != null && rcProps != null) {
            return new CachedVersatileAgentHandler(base, cache, rcProps, System::currentTimeMillis);
        }
        return base;
    }
}
