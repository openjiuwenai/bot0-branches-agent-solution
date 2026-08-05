/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.busconsumer;

import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Standalone agent runtime used to verify FEAT-017 against a real agent-bus process.
 *
 * @since 2026-07-23
 */
@SpringBootApplication
public class AgentBusConsumerDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentBusConsumerDemoApplication.class, args);
    }

    @Bean
    @ConditionalOnProperty(prefix = "demo", name = "runtime-mode", havingValue = "callee", matchIfMissing = true)
    AgentHandler calleeAgentHandler() {
        return RuntimeBusDemoAgentHandler.callee();
    }

    @Bean
    @ConditionalOnProperty(prefix = "demo", name = "runtime-mode", havingValue = "caller")
    AgentHandler callerAgentHandler() {
        return RuntimeBusDemoAgentHandler.caller();
    }
}
