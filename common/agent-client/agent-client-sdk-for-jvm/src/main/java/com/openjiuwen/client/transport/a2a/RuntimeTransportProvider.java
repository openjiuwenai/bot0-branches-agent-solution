/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.openjiuwen.client.api.RetryPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/**
 * 内置 Runtime 直连 A2A Provider。
 *
 * @since 2026-07-27
 */
public final class RuntimeTransportProvider extends A2aHttpTransportProvider {
    /**
     * 构造 Runtime 直连传输提供者，使用默认 ObjectMapper 和空闲超时。
     *
     * @param baseUrl Runtime 基址
     */
    public RuntimeTransportProvider(String baseUrl) {
        super(baseUrl, new ObjectMapper(), defaultIdleTimeout(), RuntimeEndpointPolicy.INSTANCE);
    }

    /**
     * 构造 Runtime 直连传输提供者，使用指定恢复策略。
     *
     * @param baseUrl Runtime 基址
     * @param retryPolicy 链路异常恢复策略
     */
    public RuntimeTransportProvider(String baseUrl, RetryPolicy retryPolicy) {
        super(baseUrl, new ObjectMapper(), defaultIdleTimeout(), RuntimeEndpointPolicy.INSTANCE, retryPolicy);
    }

    /**
     * 构造 Runtime 直连传输提供者。
     *
     * @param baseUrl Runtime 基址
     * @param mapper JSON 编解码器
     * @param idleTimeout SSE 读空闲超时
     */
    public RuntimeTransportProvider(String baseUrl, ObjectMapper mapper, Duration idleTimeout) {
        super(baseUrl, mapper, idleTimeout, RuntimeEndpointPolicy.INSTANCE);
    }

    /**
     * 构造 Runtime 直连传输提供者。
     *
     * @param baseUrl Runtime 基址
     * @param mapper JSON 编解码器
     * @param idleTimeout SSE 读空闲超时
     * @param retryPolicy 链路异常恢复策略
     */
    public RuntimeTransportProvider(String baseUrl, ObjectMapper mapper, Duration idleTimeout,
            RetryPolicy retryPolicy) {
        super(baseUrl, mapper, idleTimeout, RuntimeEndpointPolicy.INSTANCE, retryPolicy);
    }
}
