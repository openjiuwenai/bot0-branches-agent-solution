/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.customer.agent.customrest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.customer.agent.customrest.abcde.EdpaAbcdeCustomRestAdapter;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Verifies conditional adapter AutoConfiguration and bean registration.
 */
class EdpCustomRestAdapterAutoConfigurationTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EdpCustomRestAdapterAutoConfiguration.class));

    @Test
    void doesNotRegisterAdapterWithoutQueryPathConfigured() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(CustomRestProtocolAdapter.class);
            assertThat(context).doesNotHaveBean(EdpaAbcdeCustomRestAdapter.class);
        });
    }

    @Test
    void registersAdapterWhenQueryPathConfigured() {
        contextRunner.withUserConfiguration(Dependencies.class)
                .withPropertyValues("openjiuwen.service.custom-rest.query-path=/v1/{project_id}/agents/{agent_id}/conversations/{conversation_id}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CustomRestProtocolAdapter.class);
                    assertThat(context).hasSingleBean(EdpaAbcdeCustomRestAdapter.class);
                });
    }

    @Test
    void doesNotOverrideExistingAdapterBean() {
        contextRunner.withUserConfiguration(ExistingAdapter.class)
                .withPropertyValues("openjiuwen.service.custom-rest.query-path=/custom/{id}")
                .run(context -> {
                    assertThat(context).hasSingleBean(CustomRestProtocolAdapter.class);
                    assertThat(context.getBean(CustomRestProtocolAdapter.class))
                            .isNotInstanceOf(EdpaAbcdeCustomRestAdapter.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExistingAdapter {
        @Bean
        CustomRestProtocolAdapter customRestProtocolAdapter(ObjectMapper objectMapper) {
            return mock(CustomRestProtocolAdapter.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
