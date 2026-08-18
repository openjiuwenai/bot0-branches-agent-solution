/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.fe016;

import com.openjiuwen.rdc.controller.InstanceRouteController;
import com.openjiuwen.rdc.controller.RegistryApiExceptionHandler;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.service.AgentDiscoveryService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仿照 {@code VersatileA2AAdapterDemoApplicationTest}：验证 FEAT-016 AgentDemo
 * 的 Spring Boot 应用上下文能正常加载，关键 Bean（内存仓储、发现服务、控制器、
 * 异常处理器）均已装配。
 *
 * @since 0.1.0 (2026)
 */
@SpringBootTest
class InstanceRouteQueryDemoApplicationTest {

    @Autowired
    private AgentRegistryRepository agentRegistryRepository;

    @Autowired
    private AgentDiscoveryService agentDiscoveryService;

    @Autowired
    private InstanceRouteController instanceRouteController;

    @Autowired
    private RegistryApiExceptionHandler registryApiExceptionHandler;

    @Test
    void contextLoadsAndCoreBeansAreWired() {
        assertThat(agentRegistryRepository).isInstanceOf(InMemoryAgentRegistryRepository.class);
        assertThat(agentDiscoveryService).isNotNull();
        assertThat(instanceRouteController).isNotNull();
        assertThat(registryApiExceptionHandler).isNotNull();
    }
}
