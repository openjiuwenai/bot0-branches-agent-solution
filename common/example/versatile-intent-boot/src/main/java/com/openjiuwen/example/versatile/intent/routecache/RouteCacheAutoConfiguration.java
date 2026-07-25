/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import com.openjiuwen.example.versatile.intent.VersatileIntentAutoConfiguration;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the route-cache decorator when
 * {@code openjiuwen.service.versatile.route-cache.enabled=true}.
 *
 * <p>Loaded <em>before</em> {@link VersatileIntentAutoConfiguration} so that
 * when the cache is enabled, {@link CachedVersatileAgentHandler} claims the
 * {@code @ConditionalOnMissingBean(AgentHandler.class)} slot first; the bare
 * {@link VersatileAgentHandler} bean in {@link VersatileIntentAutoConfiguration}
 * is annotated with the complementary {@code route-cache.enabled=false}
 * condition and therefore skips. When the cache is disabled (or the property is
 * absent), this configuration contributes no beans and
 * {@link VersatileIntentAutoConfiguration} registers the bare handler.
 *
 * @since 2026-07-25
 */
@AutoConfiguration(before = VersatileIntentAutoConfiguration.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.versatile.route-cache", name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(RouteCacheProperties.class)
public class RouteCacheAutoConfiguration {
    /**
     * Process-local {@link RouteCache} backed by a {@code ConcurrentHashMap}.
     *
     * @param properties route-cache configuration (supplies TTL)
     * @return a new {@link InProcessRouteCache} using {@link System#currentTimeMillis()}
     */
    @Bean
    @ConditionalOnMissingBean(RouteCache.class)
    public RouteCache routeCache(RouteCacheProperties properties) {
        return new InProcessRouteCache(properties.getTtl(), System::currentTimeMillis);
    }

    /**
     * Decorates the bare {@link VersatileAgentHandler} with
     * {@link CachedVersatileAgentHandler}.
     *
     * @param versatileProperties the Versatile configuration properties
     * @param routeCache the route cache
     * @param routeCacheProperties the route-cache configuration properties
     * @return a caching {@link AgentHandler}
     */
    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    public AgentHandler cachedVersatileAgentHandler(VersatileProperties versatileProperties,
                                                    RouteCache routeCache,
                                                    RouteCacheProperties routeCacheProperties) {
        VersatileAgentHandler bare = new VersatileAgentHandler(versatileProperties);
        return new CachedVersatileAgentHandler(bare, routeCache, routeCacheProperties,
                System::currentTimeMillis);
    }
}
