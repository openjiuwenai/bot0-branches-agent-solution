/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.edp.handler.EdpaExtHandler.InitResult;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tests for {@link EdpAgentFactoryAutoConfiguration}.
 *
 * <p>Verifies the conditional bean registration logic:
 * <ul>
 *     <li>Bean created when InitResult is available</li>
 *     <li>Bean skipped when InitResult is missing</li>
 *     <li>Bean skipped when a custom AgentFactory already exists</li>
 * </ul>
 *
 * @since 0.1.2
 */
class EdpAgentFactoryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EdpAgentFactoryAutoConfiguration.class));

    @Test
    void whenInitResultPresent_registersEdpAgentFactory() {
        contextRunner
                .withUserConfiguration(InitResultProvider.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentFactory.class);
                    assertThat(context.getBean(AgentFactory.class))
                            .isInstanceOf(com.openjiuwen.edp.handler.EdpAgentFactory.class);
                });
    }

    @Test
    void whenInitResultMissing_doesNotRegisterFactory() {
        contextRunner
                .run(context ->
                        assertThat(context).doesNotHaveBean(AgentFactory.class));
    }

    @Test
    void whenCustomAgentFactoryExists_doesNotOverride() {
        contextRunner
                .withUserConfiguration(InitResultProvider.class, CustomAgentFactoryProvider.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentFactory.class);
                    assertThat(context.getBean(AgentFactory.class))
                            .isNotInstanceOf(com.openjiuwen.edp.handler.EdpAgentFactory.class);
                });
    }

    // --- Test configuration providers ---

    @Configuration(proxyBeanMethods = false)
    static class InitResultProvider {
        @Bean
        InitResult edpInitResult() {
            DeepAgent mockDeepAgent = mock(DeepAgent.class);
            when(mockDeepAgent.getCard()).thenReturn(
                    AgentCard.builder().id("test-agent").name("Test Agent").build());
            when(mockDeepAgent.getConfig()).thenReturn(new DeepAgentConfig());
            InitResult result = new InitResult();
            result.setDeepAgent(mockDeepAgent);
            return result;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomAgentFactoryProvider {
        @Bean
        AgentFactory customAgentFactory() {
            return mock(AgentFactory.class);
        }
    }
}
