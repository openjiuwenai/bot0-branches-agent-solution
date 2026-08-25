/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.RetryPolicy;
import com.openjiuwen.client.transport.spi.RawResponseObserver;
import com.openjiuwen.client.transport.spi.RawFrameConsumer;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * 内置 Gateway A2A Provider。
 *
 * @since 2026-07-27
 */
public final class GatewayTransportProvider extends A2aHttpTransportProvider {
    /**
     * 构造 Gateway 传输提供者，使用默认 ObjectMapper 和空闲超时。
     *
     * @param baseUrl 网关基址
     */
    public GatewayTransportProvider(String baseUrl) {
        super(baseUrl, new ObjectMapper(), defaultIdleTimeout(), GatewayEndpointPolicy.INSTANCE);
    }

    /**
     * 构造 Gateway 传输提供者，使用指定恢复策略。
     *
     * @param baseUrl 网关基址
     * @param retryPolicy 链路异常恢复策略
     */
    public GatewayTransportProvider(String baseUrl, RetryPolicy retryPolicy) {
        super(baseUrl, new ObjectMapper(), defaultIdleTimeout(), GatewayEndpointPolicy.INSTANCE, retryPolicy);
    }

    /**
     * 构造 Gateway 传输提供者，配置结构化原始响应观察器及异步分发策略。
     *
     * @param baseUrl 网关基址
     * @param retryPolicy 链路异常恢复策略
     * @param observer 结构化原始响应观察器
     * @param observerExecutor 观察回调执行器
     * @param observerQueueCapacity 观察队列容量，满时丢弃最新观察记录
     * @param observerFlushTimeout 关闭时等待观察队列排空的最长时间
     */
    public GatewayTransportProvider(String baseUrl, RetryPolicy retryPolicy, RawResponseObserver observer,
            java.util.concurrent.Executor observerExecutor, int observerQueueCapacity,
            Duration observerFlushTimeout) {
        super(baseUrl, new ObjectMapper(), defaultIdleTimeout(), GatewayEndpointPolicy.INSTANCE,
                retryPolicy, observer, observerExecutor, observerQueueCapacity, observerFlushTimeout);
    }

    /** @deprecated use the structured observer constructor. */
    @Deprecated(forRemoval = false)
    public GatewayTransportProvider(String baseUrl, RetryPolicy retryPolicy, RawFrameConsumer consumer) {
        this(baseUrl, retryPolicy, event -> consumer.accept(event.invocationRef(), event.conversationId(),
                event.body(), event.source().name()), java.util.concurrent.ForkJoinPool.commonPool(),
                65536, Duration.ofSeconds(5));
    }

    /**
     * 构造 Gateway 传输提供者。
     *
     * @param baseUrl 网关基址
     * @param mapper JSON 编解码器
     * @param idleTimeout SSE 读空闲超时
     */
    public GatewayTransportProvider(String baseUrl, ObjectMapper mapper, Duration idleTimeout) {
        super(baseUrl, mapper, idleTimeout, GatewayEndpointPolicy.INSTANCE);
    }

    /**
     * 构造 Gateway 传输提供者。
     *
     * @param baseUrl 网关基址
     * @param mapper JSON 编解码器
     * @param idleTimeout SSE 读空闲超时
     * @param retryPolicy 链路异常恢复策略
     */
    public GatewayTransportProvider(String baseUrl, ObjectMapper mapper, Duration idleTimeout,
            RetryPolicy retryPolicy) {
        super(baseUrl, mapper, idleTimeout, GatewayEndpointPolicy.INSTANCE, retryPolicy);
    }

    /**
     * 构造 Gateway 传输提供者。
     *
     * @param baseUrl 网关基址
     * @param mapper JSON 编解码器
     * @param idleTimeout SSE 读空闲超时
     * @param retryPolicy 链路异常恢复策略
     * @param observer 结构化原始响应观察器
     * @param observerExecutor 观察回调执行器
     * @param observerQueueCapacity 观察队列容量，满时丢弃最新观察记录
     * @param observerFlushTimeout 关闭时等待观察队列排空的最长时间
     */
    public GatewayTransportProvider(String baseUrl, ObjectMapper mapper, Duration idleTimeout,
            RetryPolicy retryPolicy, RawResponseObserver observer, java.util.concurrent.Executor observerExecutor,
            int observerQueueCapacity, Duration observerFlushTimeout) {
        super(baseUrl, mapper, idleTimeout, GatewayEndpointPolicy.INSTANCE, retryPolicy, observer,
                observerExecutor, observerQueueCapacity, observerFlushTimeout);
    }

    /** @deprecated use the structured observer constructor. */
    @Deprecated(forRemoval = false)
    public GatewayTransportProvider(String baseUrl, ObjectMapper mapper, Duration idleTimeout,
            RetryPolicy retryPolicy, RawFrameConsumer consumer) {
        this(baseUrl, mapper, idleTimeout, retryPolicy,
                event -> consumer.accept(event.invocationRef(), event.conversationId(),
                        event.body(), event.source().name()), java.util.concurrent.ForkJoinPool.commonPool(),
                65536, Duration.ofSeconds(5));
    }
}
