/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.adapter;

import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * versatile-adapter on agent-solution: a thin A2A front around the versatile mock.
 * The {@link VersatileAgentHandler} reconstructs the bank REST call from the
 * request, streams the downstream SSE, and extracts node_type=QA results.
 *
 * @since 2026-07-08
 */
@SpringBootApplication
public class VersatileAdapterApplication {
    public static void main(String[] args) {
        SpringApplication.run(VersatileAdapterApplication.class, args);
    }

    @Bean
    AgentHandler versatileCallHandler(VersatileProperties properties) {
        return new VersatileAgentHandler(properties);
    }
}
