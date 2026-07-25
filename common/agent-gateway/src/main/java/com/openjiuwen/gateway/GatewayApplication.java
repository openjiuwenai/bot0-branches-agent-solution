/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone entry point for agent-gateway (FEAT-011). Component scan covers
 * {@code com.openjiuwen.gateway} so the facade, governance pipeline, routing,
 * direct/sse forwarders and ports are picked up automatically.
 *
 * <p>The gateway is an independently deployable unit (L2 §1.6): it boots
 * standalone and reaches RDC / runtime over HTTP/SSE via ports — it does not
 * require co-process deployment with the registry or runtime.
 *
 * @since 0.1.0
 */
@SpringBootApplication
public class GatewayApplication {
    /**
     * main.
     *
     * @param args args
     * @since 0.1.0
     */
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
