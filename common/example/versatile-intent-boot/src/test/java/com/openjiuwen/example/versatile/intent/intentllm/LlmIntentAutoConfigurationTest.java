/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.intentllm;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

/**
 * Tests for {@link LlmIntentAutoConfiguration} bean registration and gating.
 *
 * @since 0.1.0
 */
class LlmIntentAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(VersatileConfig.class)
            .withConfiguration(AutoConfigurations.of(LlmIntentAutoConfiguration.class));

    @Test
    void registersBareHandlerWhenEnabledWithoutRouteCache() {
        runner.withPropertyValues(
                "openjiuwen.example.intent-llm.enabled=true",
                "openjiuwen.example.intent-llm.api-key=k",
                "openjiuwen.example.intent-llm.base-url=u",
                "openjiuwen.example.intent-llm.model=m")
                .run(ctx -> {
                    AgentHandler h = ctx.getBean(AgentHandler.class);
                    assertThat(h).isInstanceOf(LlmIntentAgentHandler.class);
                });
    }

    @Test
    void doesNotRegisterWhenDisabled() {
        runner.withPropertyValues("openjiuwen.example.intent-llm.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AgentHandler.class));
    }

    static class VersatileConfig {
        @Bean
        VersatileProperties versatileProperties() {
            VersatileProperties p = new VersatileProperties();
            p.setAmbiguousIntentId("1");
            return p;
        }
    }
}
