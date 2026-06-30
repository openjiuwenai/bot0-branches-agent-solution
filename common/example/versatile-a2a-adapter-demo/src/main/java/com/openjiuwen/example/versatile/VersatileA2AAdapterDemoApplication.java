/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile;

import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot application for the Versatile A2A adapter demo.
 *
 * @since 2026-06-30
 */
@SpringBootApplication
public class VersatileA2AAdapterDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(VersatileA2AAdapterDemoApplication.class, args);
    }

    @Bean
    AgentHandler versatileAgentHandler(VersatileProperties properties) {
        return new VersatileAgentHandler(properties);
    }
}
