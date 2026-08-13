/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/** 内置 Runtime 直连 A2A Provider。 */
public final class RuntimeTransportProvider extends A2aHttpTransportProvider {
    public RuntimeTransportProvider(String baseUrl) {
        super(baseUrl, new ObjectMapper(), defaultIdleTimeout(), RuntimeEndpointPolicy.INSTANCE);
    }

    public RuntimeTransportProvider(String baseUrl, ObjectMapper mapper, Duration idleTimeout) {
        super(baseUrl, mapper, idleTimeout, RuntimeEndpointPolicy.INSTANCE);
    }
}
