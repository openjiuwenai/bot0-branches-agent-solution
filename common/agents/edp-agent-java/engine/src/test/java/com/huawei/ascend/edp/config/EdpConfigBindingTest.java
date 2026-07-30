/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
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

package com.huawei.ascend.edp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

/**
 * 端到端配置绑定测试：验证压平后的 deep-agent.* 前缀经 Spring 松散绑定正确注入
 * 各 @ConfigurationProperties bean。
 *
 * <p>测试上下文仅注册 4 个 @ConfigurationProperties bean（不扫描 RedisConfig /
 * EdpEngineConfiguration / 自动配置），故无需 Redis、模型后端或 MCP 等外部依赖。</p>
 *
 * <p>验证策略：</p>
 * <ul>
 *   <li>覆盖断言：通过 @SpringBootTest(properties=...) 注入 deep-agent.* 覆盖值，
 *       断言 bean 字段接收覆盖值（而非类字段默认值）。若任一前缀仍为旧的
 *       deep-agent.agent.*，覆盖值不会绑定，断言将失败。</li>
 *   <li>真实加载断言：对未覆盖的字段（类默认为 null），断言非 null 以证明
 *       application.yml 被真实加载并绑定，而非仅靠覆盖机制。</li>
 *   <li>共存断言：EdpaSpringBootConfig 与 DeepAgentProperties 均绑定 deep-agent 前缀，
 *       字段互不重叠（前者无 backend/model，后者无 scenarioHome/versatile/mcpsse/sandbox），
 *       上下文顺利加载即证明同前缀多 bean 无冲突。</li>
 * </ul>
 */
@SpringBootTest(classes = EdpConfigBindingTest.TestConfig.class, properties = {
        "deep-agent.scenario-home=test-e2e-scenario",
        "deep-agent.redis.host=test-redis-host",
        "deep-agent.sandbox.sandbox-id-prefix=test-prefix",
        "deep-agent.backend.client-provider=test-provider",
        "deep-agent.versatile.circuit-breaker.failure-threshold=99",
        "deep-agent.mcpsse.app-name=test-app-name"
})
class EdpConfigBindingTest {

    @Configuration
    @EnableConfigurationProperties({
            EdpaSpringBootConfig.class,
            DeepAgentProperties.class,
            TodoRedisProperties.class,
            SandboxConfig.class
    })
    static class TestConfig {
    }

    @Autowired
    private EdpaSpringBootConfig edpaConfig;

    @Autowired
    private DeepAgentProperties deepAgentProperties;

    @Autowired
    private TodoRedisProperties redisProps;

    @Autowired
    private SandboxConfig sandboxConfig;

    @Test
    void scenarioHome_bindsFromFlattenedPrefix() {
        // 覆盖值证明走 deep-agent.scenario-home（非旧 deep-agent.agent.scenario-home）
        assertEquals("test-e2e-scenario", edpaConfig.getScenarioHome());
    }

    @Test
    void redisHost_bindsFromFlattenedPrefix() {
        // 覆盖值证明走 deep-agent.redis.host（非旧 deep-agent.agent.redis.host）
        assertEquals("test-redis-host", redisProps.getHost());
    }

    @Test
    void sandboxIdPrefix_bindsFromFlattenedPrefix() {
        // 覆盖值证明走 deep-agent.sandbox.sandbox-id-prefix（非旧 deep-agent.agent.sandbox.*）
        assertEquals("test-prefix", sandboxConfig.getSandboxIdPrefix());
    }

    @Test
    void backendClientProvider_bindsAtTopLevel() {
        // 覆盖值证明 deep-agent.backend.* 仍绑定到 DeepAgentProperties（顶层无回归）
        assertNotNull(deepAgentProperties.getBackend());
        assertEquals("test-provider", deepAgentProperties.getBackend().getClientProvider());
    }

    @Test
    void versatileCircuitBreaker_bindsNestedOnEdpaConfig() {
        // 覆盖值证明 deep-agent.versatile.circuit-breaker.* 绑定到 EdpaSpringBootConfig 嵌套字段
        assertNotNull(edpaConfig.getVersatile());
        assertNotNull(edpaConfig.getVersatile().getCircuitBreaker());
        assertEquals(99, edpaConfig.getVersatile().getCircuitBreaker().getFailureThreshold());
    }

    @Test
    void mcpsseAppName_bindsNestedOnEdpaConfig() {
        // 覆盖值证明 deep-agent.mcpsse.* 绑定到 EdpaSpringBootConfig 嵌套字段
        assertNotNull(edpaConfig.getMcpsse());
        assertEquals("test-app-name", edpaConfig.getMcpsse().getAppName());
    }

    @Test
    void realApplicationYml_isLoadedAndBound() {
        // 未覆盖字段（类默认 null）非 null，证明真实 application.yml 被加载并绑定
        assertNotNull(deepAgentProperties.getModel(), "deep-agent.model.* 应从 application.yml 绑定");
        assertNotNull(deepAgentProperties.getModel().getModel(), "deep-agent.model.model 应非空");
        assertNotNull(edpaConfig.getMcpsse().getMasterUrl(), "deep-agent.mcpsse.master-url 应非空");
    }
}
