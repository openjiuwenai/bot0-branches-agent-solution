/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;

/** 内置 Gateway A2A Provider。 */
public final class GatewayTransportProvider extends A2aHttpTransportProvider {
    public GatewayTransportProvider(String baseUrl) {
        super(baseUrl, new ObjectMapper(), defaultIdleTimeout(), GatewayEndpointPolicy.INSTANCE);
    }

    public GatewayTransportProvider(String baseUrl, ObjectMapper mapper, Duration idleTimeout) {
        super(baseUrl, mapper, idleTimeout, GatewayEndpointPolicy.INSTANCE);
    }
}
