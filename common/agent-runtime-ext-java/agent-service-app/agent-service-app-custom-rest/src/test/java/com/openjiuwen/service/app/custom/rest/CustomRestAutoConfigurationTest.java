/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CustomRestAutoConfigurationTest {
    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CustomRestAutoConfiguration.class));

    @Test
    void doesNotEnableHandlerWithoutConfiguredPath() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(CustomRestA2ABridge.class);
            assertThat(context).doesNotHaveBean(CustomRestAutoConfiguration.CustomRestHandler.class);
        });
    }

    @Test
    void registersExactlyOneBridgeAndHandlerWhenConfigured() {
        contextRunner.withUserConfiguration(Dependencies.class)
                .withPropertyValues("openjiuwen.service.custom-rest.query-path=/custom/{id}")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(CustomRestA2ABridge.class);
                    assertThat(context).hasSingleBean(CustomRestAutoConfiguration.CustomRestHandler.class);
                });
    }

    @Test
    void queryPathMustBeNonBlankAndAbsolute() {
        assertThatThrownBy(() -> CustomRestAutoConfiguration.validateQueryPath(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomRestAutoConfiguration.validateQueryPath("relative/{id}"))
                .isInstanceOf(IllegalArgumentException.class);
        CustomRestAutoConfiguration.validateQueryPath("/custom/{id}");
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {
        @Bean
        CustomRestProtocolAdapter customRestProtocolAdapter() {
            return mock(CustomRestProtocolAdapter.class);
        }

        @Bean
        RequestHandler requestHandler() {
            return mock(RequestHandler.class);
        }

        @Bean
        TaskStore taskStore() {
            return mock(TaskStore.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
