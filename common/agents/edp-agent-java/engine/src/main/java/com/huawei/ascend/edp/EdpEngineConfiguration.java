/*
 * Copyright 2026 Huawei Technologies Co., Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.huawei.ascend.edp;

import com.huawei.ascend.edp.config.EdpaSpringBootConfig;
import com.huawei.ascend.edp.handler.EdpaExtHandler;
import com.huawei.ascend.edp.todo.RedisTodoStore;

import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreSandboxClientFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
/**
 * EDPAgent Spring Bean 配置（适配版，Phase 2 合并后）。
 *
 * <p>与原版 {@code EdpaEngineConfiguration} 的核心差异：</p>
 * <ul>
 *     <li>注册 {@link AgentHandler} Bean（适配版SPI），替代原版 AgentRuntimeHandler SPI。</li>
 *     <li>使用 {@link EdpaSpringBootConfig} 统一管理全部配置属性（Phase 2 合并 EdpAgentProperties 到 EdpaSpringBootConfig），
 *         Spring Boot 原生支持 ${ENV_VAR:default} 占位符，不再需要手写 resolveEnvOverrides。</li>
 *     <li>注入 RedisTodoStore（0707新增的Redis Todo持久化），传入 EdpaExtHandler.init()。</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EdpaSpringBootConfig.class)
/**
 * EdpEngineConfiguration class.
 *
 * @since 2024-01-01
 */
public class EdpEngineConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(EdpEngineConfiguration.class);

    /**
     * 创建并初始化 EDPAgent AgentHandler Bean。
     *
     * <p>适配版通过 {@link AgentHandler} SPI接入 agent-runtime-java，
     * 不再使用原版 AgentRuntimeHandler SPI。</p>
     *
     * <p>Phase 2 变化：使用 EdpaSpringBootConfig（合并了原 EdpAgentProperties），
     * model/versatile/mcpsse 配置直接从 Spring Boot 绑定获取，
     * 不再需要 EnvOverrides 手动覆盖（Spring Boot ${ENV_VAR:default} 自动处理）。</p>
     *
     * @param config EDPAgent 合并后配置属性（含 scenarioHome/model/versatile/mcpsse）
     * @return 已初始化的 AgentHandler Bean
     */
    @Bean
    AgentHandler edpaExtHandler(EdpaSpringBootConfig config,
            @Value("${openjiuwen.service.a2a.agent-name:EDPAgent}") String agentName,
            ObjectProvider<AgentCoreSandboxClientFactory> sandboxClientFactoryProvider,
            ObjectProvider<RedisTodoStore> redisTodoStoreProvider) {
        // 从 Spring 容器注入 RedisTodoStore（确保 Bean 创建顺序：redisTodoStore 先于 edpaExtHandler）
        RedisTodoStore redisTodoStore = redisTodoStoreProvider.getIfAvailable();
        if (redisTodoStore == null) {
            LOGGER.warn("RedisTodoStore not available (Redis not enabled), Todo will use file fallback");
        }

        // 需求2：通过 agent-runtime-java 中转获取治理装饰 SandboxClient
        SandboxClient decoratedSandboxClient = resolveDecoratedSandboxClient(sandboxClientFactoryProvider);

        // Bean 创建阶段先完成全部初始化，获取真实 agent 实例
        EdpaExtHandler.InitResult initResult = EdpaExtHandler.performInit(config, redisTodoStore, agentName,
                decoratedSandboxClient);

        // 用真实 agent 实例构造 Handler，消除反射 hack
        EdpaExtHandler handler = new EdpaExtHandler(initResult.agentInstance);
        handler.applyInitResult(initResult);

        LOGGER.info(
                "EdpEngineConfiguration: edpaExtHandler bean created, agentId={}, "
                        + "deepAgent initialized={}, sandboxPath={}",
                agentName, initResult.deepAgent.isInitialized(),
                decoratedSandboxClient != null ? "governed(Path2)" : "direct(Path1)");
        return handler;
    }

    /**
     * 需求2：通过 AgentCoreSandboxClientFactory 创建 DecoratingSandboxClient。
     *
     * <p>当 openjiuwen.service.external.sandbox.enabled=true 时，Spring 条件装配自动创建
     * DefaultAgentCoreSandboxClientFactory Bean。调用 factory.create() 返回的 DecoratingSandboxClient
     * 包含熔断/重试/审计/结构化错误码治理能力。</p>
     *
     * <p>当 factory 不存在（需求1路径）或创建失败时返回 null，降级为 SysOperation 直接模式。</p>
     *
     * @param provider Spring ObjectProvider，可能不存在
     * @return DecoratingSandboxClient 实例，或 null（降级到需求1直接模式）
     */
    private static SandboxClient resolveDecoratedSandboxClient(ObjectProvider<AgentCoreSandboxClientFactory> provider) {
        AgentCoreSandboxClientFactory factory = provider.getIfAvailable();
        if (factory == null) {
            LOGGER.info("[EDP-SANDBOX] AgentCoreSandboxClientFactory absent, using Path 1 (direct)");
            return null;
        }
        try {
            SandboxClient client = factory.create();
            LOGGER.info("[EDP-SANDBOX] DecoratingSandboxClient created via factory (Path 2: governed)");
            return client;
        } catch (RuntimeException e) {
            LOGGER.warn("[EDP-SANDBOX] Failed to create DecoratingSandboxClient, falling back to Path 1: {}",
                    e.getMessage());
            return null;
        }
    }
}
