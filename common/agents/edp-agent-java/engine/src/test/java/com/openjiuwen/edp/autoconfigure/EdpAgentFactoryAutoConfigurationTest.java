/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
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
 *     <li>Bean created when all conditions are met</li>
 *     <li>Bean skipped when AgentCard is missing</li>
 *     <li>Bean skipped when DeepAgentConfig is missing</li>
 *     <li>Bean skipped when a custom AgentFactory already exists</li>
 * </ul>
 *
 * @since 0.1.2
 */
class EdpAgentFactoryAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EdpAgentFactoryAutoConfiguration.class));

    @Test
    void whenAllConditionsMet_registersEdpAgentFactory() {
        contextRunner
                .withUserConfiguration(FullConfigProvider.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentFactory.class);
                    assertThat(context.getBean(AgentFactory.class))
                            .isInstanceOf(com.openjiuwen.edp.handler.EdpAgentFactory.class);
                });
    }

    @Test
    void whenAgentCardMissing_doesNotRegisterFactory() {
        contextRunner
                .withUserConfiguration(OnlyDeepAgentConfigProvider.class)
                .run(context ->
                        assertThat(context).doesNotHaveBean(AgentFactory.class));
    }

    @Test
    void whenDeepAgentConfigMissing_doesNotRegisterFactory() {
        contextRunner
                .withUserConfiguration(OnlyAgentCardProvider.class)
                .run(context ->
                        assertThat(context).doesNotHaveBean(AgentFactory.class));
    }

    @Test
    void whenCustomAgentFactoryExists_doesNotOverride() {
        contextRunner
                .withUserConfiguration(FullConfigProvider.class, CustomAgentFactoryProvider.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(AgentFactory.class);
                    assertThat(context.getBean(AgentFactory.class))
                            .isNotInstanceOf(com.openjiuwen.edp.handler.EdpAgentFactory.class);
                });
    }

    @Test
    void whenNoConfigBeans_doesNotRegisterFactory() {
        contextRunner
                .run(context ->
                        assertThat(context).doesNotHaveBean(AgentFactory.class));
    }

    // --- Test configuration providers ---

    @Configuration(proxyBeanMethods = false)
    static class FullConfigProvider {
        @Bean
        AgentCard edpAgentCard() {
            return AgentCard.builder().id("test-agent").name("Test Agent").build();
        }

        @Bean
        DeepAgentConfig edpDeepAgentConfig() {
            return new DeepAgentConfig();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OnlyAgentCardProvider {
        @Bean
        AgentCard edpAgentCard() {
            return AgentCard.builder().id("test-agent").name("Test Agent").build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OnlyDeepAgentConfigProvider {
        @Bean
        DeepAgentConfig edpDeepAgentConfig() {
            return new DeepAgentConfig();
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
