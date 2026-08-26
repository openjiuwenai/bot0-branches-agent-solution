/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceIdentityFilter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.runtree.RunTreeRegistrar;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

/**
 * 全链路轨迹（第二批）装配：仅在 {@code openjiuwen.service.trajectory.link.enabled=true}
 * 时生效，不启用时宿主零行为变化。与第一批 OTel 相互独立，可分别启停。
 *
 * <p>enabled=true 但 Redis 未配置（{@link RuntimeRedisClient} 不在 classpath/未注册 Bean）
 * 时启动 WARN 并保持关闭（V-8 口径）——下游消费方一律经 ObjectProvider 注入，缺失即不装配。
 *
 * @since 2026-08-26
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "openjiuwen.service.trajectory.link", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TrajectoryLinkProperties.class)
public class TrajectoryLinkAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(TrajectoryLinkAutoConfiguration.class);

    /**
     * Provides the shared trace context carrier.
     *
     * @param props link properties
     * @return the carrier
     */
    @Bean
    TraceContextCarrier traceContextCarrier(TrajectoryLinkProperties props) {
        return TraceContextCarrier.create(props.getCarrierTtlSeconds());
    }

    /**
     * Provides the Redis key-space store. Returns null (WARN, capability stays off)
     * when no RuntimeRedisClient bean is available.
     *
     * @param redisClientProvider redis client provider
     * @param props               link properties
     * @return the store, or null when Redis is unavailable
     */
    @Bean
    RedisTrajectoryStore redisTrajectoryStore(ObjectProvider<RuntimeRedisClient> redisClientProvider,
                                              TrajectoryLinkProperties props) {
        RuntimeRedisClient client = redisClientProvider.getIfAvailable();
        if (client == null) {
            LOGGER.warn("trajectory.link enabled but RuntimeRedisClient is not available; "
                    + "capability stays OFF (configure FEAT-003 Redis to enable)");
            return null;
        }
        return new RedisTrajectoryStore(client, props.effectiveTtlSeconds());
    }

    /**
     * Provides the async batch writer. Returns null when the store is unavailable.
     *
     * @param storeProvider store provider
     * @param props         link properties
     * @return the started writer, or null when Redis is unavailable
     */
    @Bean
    AsyncTrajectoryWriter asyncTrajectoryWriter(ObjectProvider<RedisTrajectoryStore> storeProvider,
                                                TrajectoryLinkProperties props) {
        RedisTrajectoryStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return null;
        }
        AsyncTrajectoryWriter writer = new AsyncTrajectoryWriter(props.getQueueCapacity(), props.getFlushIntervalMs());
        writer.start();
        return writer;
    }

    /**
     * Registers the trace identity filter ahead of the batch-1 http span filter
     * (batch-1 uses order 0; a smaller value runs first).
     *
     * @param carrier           trace context carrier
     * @param taskStoreProvider SDK task store provider (nullable)
     * @param storeProvider     trajectory store provider (nullable)
     * @return filter registration
     */
    @Bean
    FilterRegistrationBean<TraceIdentityFilter> traceIdentityFilter(
            TraceContextCarrier carrier, ObjectProvider<TaskStore> taskStoreProvider,
            ObjectProvider<RedisTrajectoryStore> storeProvider) {
        FilterRegistrationBean<TraceIdentityFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceIdentityFilter(carrier, taskStoreProvider.getIfAvailable(),
                storeProvider.getIfAvailable()));
        registration.addUrlPatterns("/a2a", "/a2a/*", "/v1/*");
        registration.setOrder(-10);
        return registration;
    }

    /**
     * Registers the run-tree TaskStore decorator post processor.
     *
     * @param carrierProvider carrier provider
     * @param storeProvider   store provider
     * @param writerProvider  writer provider
     * @return the registrar
     */
    @Bean
    RunTreeRegistrar runTreeRegistrar(ObjectProvider<TraceContextCarrier> carrierProvider,
                                      ObjectProvider<RedisTrajectoryStore> storeProvider,
                                      ObjectProvider<AsyncTrajectoryWriter> writerProvider) {
        return new RunTreeRegistrar(carrierProvider, storeProvider, writerProvider);
    }
}
