/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.buscaller;

import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Source Runtime used to verify Runtime-to-Runtime calls through Agent Bus.
 *
 * @since 2026-08-12
 */
@SpringBootApplication
public class AgentBusCallerDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentBusCallerDemoApplication.class, args);
    }

    @Bean
    AgentHandler callerAgentHandler() {
        return new CallerAgentHandler();
    }
}
