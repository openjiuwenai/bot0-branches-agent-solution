/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.agent_b;

import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot application for the remote Versatile demo runtime.
 *
 * @since 2026-06-30
 */
@SpringBootApplication
public class VersatileRuntimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(VersatileRuntimeApplication.class, args);
    }

    @Bean
    AgentHandler versatileAgentHandler(VersatileProperties properties) {
        return new VersatileAgentHandler(properties);
    }
}
