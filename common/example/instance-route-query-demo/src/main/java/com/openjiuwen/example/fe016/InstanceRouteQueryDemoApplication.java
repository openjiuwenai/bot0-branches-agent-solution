/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.fe016;

import com.openjiuwen.example.fe016.agent.Fe016StubAgentHandler;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.controller.InstanceRouteController;
import com.openjiuwen.rdc.controller.RegistryApiExceptionHandler;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.service.AgentDiscoveryService;
import com.openjiuwen.rdc.service.PgMvpDiscoveryServiceImpl;
import com.openjiuwen.rdc.tenant.TenantContext;
import com.openjiuwen.rdc.tenant.ThreadLocalTenantContext;
import com.openjiuwen.service.spec.spi.AgentHandler;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * FEAT-016 运行时实例路由查询 AgentDemo — Spring Boot 启动入口。
 *
 * <p>仿照 {@code versatile-a2a-adapter-demo} 的模式：一个 {@code @SpringBootApplication}
 * 启动类 + 一个独立 CLI 客户端（{@link com.openjiuwen.example.fe016.client.InstanceRouteQueryClientMain}）。
 *
 * <p>装配策略：只扫描 demo 自身包（{@code com.openjiuwen.example.fe016}），
 * 手动用 {@code @Bean} 装配 registry 模块里 FEAT-016 需要的 Bean —— 内存版
 * {@link AgentRegistryRepository}（无需 PostgreSQL / Flyway）、
 * {@link PgMvpDiscoveryServiceImpl}、{@link ThreadLocalTenantContext}、
 * {@link RegistryObservabilityConfig}、{@link InstanceRouteController}、
 * {@link RegistryApiExceptionHandler}。registry 模块里依赖 {@code DataSource}
 * 的 {@code RegistryRuntimeBeanConfig} / {@code JdbcAgentRegistryRepository} /
 * 探活调度器均不装配（其包不在扫描范围内）。
 *
 * @since 0.1.0 (2026)
 */
@SpringBootApplication
public class InstanceRouteQueryDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(InstanceRouteQueryDemoApplication.class, args);
    }

    /**
     * 目标 Agent 的业务实现（无 LLM，确定性 mock）。
     *
     * <p>这是让本 demo 成为「真 AgentDemo」的关键 Bean：{@code agent-service-app}
     * 检测到本 Bean 后自动在 {@code /a2a} 暴露 A2A JSON-RPC 端点。注册中心 seed
     * 数据中实例 #1 的 {@code endpointUrl} 指向本进程（{@code http://127.0.0.1:18090}），
     * 客户端 resolve 得到该 URL 后用 A2A 协议回环调用本 Agent。
     *
     * @return the stub agent handler bean
     */
    @Bean
    public AgentHandler fe016AgentHandler() {
        return new Fe016StubAgentHandler();
    }

    /**
     * 内存版 AgentRegistryRepository Bean，预置多租户种子数据，无需 PostgreSQL。
     *
     * @return the in-memory agent registry repository bean
     */
    @Bean
    public AgentRegistryRepository agentRegistryRepository() {
        return new InMemoryAgentRegistryRepository();
    }

    /**
     * ThreadLocal 租户上下文 Bean。
     *
     * @return the tenant context bean
     */
    @Bean
    public TenantContext tenantContext() {
        return new ThreadLocalTenantContext();
    }

    /**
     * SimpleMeterRegistry Bean，用于 demo 指标收集。
     *
     * @return the meter registry bean
     */
    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * 注册中心可观测性配置 Bean。
     *
     * @param meterRegistry the meter registry
     * @return the registry observability config bean
     */
    @Bean
    public RegistryObservabilityConfig registryObservabilityConfig(MeterRegistry meterRegistry) {
        return new RegistryObservabilityConfig(meterRegistry);
    }

    /**
     * AgentDiscoveryService Bean，基于 PgMvpDiscoveryServiceImpl。
     *
     * @param repository    the agent registry repository
     * @param tenantContext the tenant context
     * @param observability the registry observability config
     * @return the agent discovery service bean
     */
    @Bean
    public AgentDiscoveryService agentDiscoveryService(
            AgentRegistryRepository repository,
            TenantContext tenantContext,
            RegistryObservabilityConfig observability) {
        // callerAuthorizationPolicy 传 null → PgMvpDiscoveryServiceImpl 内部退化为 Permissive
        return new PgMvpDiscoveryServiceImpl(repository, tenantContext, observability, null);
    }

    /**
     * InstanceRouteController Bean，提供实例路由查询与 route-handle 解析端点。
     *
     * @param discovery  the agent discovery service
     * @param repository  the agent registry repository
     * @return the instance route controller bean
     */
    @Bean
    public InstanceRouteController instanceRouteController(
            AgentDiscoveryService discovery,
            AgentRegistryRepository repository) {
        return new InstanceRouteController(discovery, repository);
    }

    /**
     * RegistryApiExceptionHandler Bean，处理注册中心异常到 HTTP 状态码的映射。
     *
     * @return the registry api exception handler bean
     */
    @Bean
    public RegistryApiExceptionHandler registryApiExceptionHandler() {
        return new RegistryApiExceptionHandler();
    }
}
