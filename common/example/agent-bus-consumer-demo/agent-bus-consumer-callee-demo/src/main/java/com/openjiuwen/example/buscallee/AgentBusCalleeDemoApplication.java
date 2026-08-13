/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.buscallee;

import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Target Runtime used by Agent Bus and agent-client E2E scenarios.
 *
 * @since 2026-08-12
 */
@SpringBootApplication
public class AgentBusCalleeDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentBusCalleeDemoApplication.class, args);
    }

    @Bean
    AgentHandler calleeAgentHandler() {
        return CalleeAgentHandler.standard();
    }
}
