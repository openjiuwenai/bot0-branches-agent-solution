/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.reclassify;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallbackHandler;
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;

/**
 * Verifies {@link ReclassifyAutoConfiguration} registers the post-processor
 * only when {@code intent-reclassify.enabled=true}.
 *
 * @since 2026-07-24
 */
class ReclassifyAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

    @Test
    void registersPostProcessorWhenEnabled() {
        contextRunner
                .withUserConfiguration(MockOrchestratorConfig.class)
                .withUserConfiguration(ReclassifyAutoConfiguration.class)
                .withPropertyValues("openjiuwen.service.versatile.intent-reclassify.enabled=true")
                .run(context -> {
                    ServeOrchestrator orchestrator = context.getBean(ServeOrchestrator.class);
                    // Issue #50 regression: the wrapped bean must still expose the
                    // runtime's A2aPushNotificationCallbackHandler interface, and
                    // callback-handler injection must resolve to that same bean.
                    assertThat(orchestrator).isInstanceOf(A2aPushNotificationCallbackHandler.class);
                    assertThat(context.getBean(A2aPushNotificationCallbackHandler.class)).isSameAs(orchestrator);
                    // The post-processor replaced the raw bean with a proxy.
                    assertThat(Proxy.isProxyClass(orchestrator.getClass())).isTrue();
                    assertThat(context.getBean(ReclassifyProperties.class).isEnabled()).isTrue();
                });
    }

    @Test
    void doesNotRegisterPostProcessorWhenDisabled() {
        contextRunner
                .withUserConfiguration(MockOrchestratorConfig.class)
                .withUserConfiguration(ReclassifyAutoConfiguration.class)
                .withPropertyValues("openjiuwen.service.versatile.intent-reclassify.enabled=false")
                .run(context -> {
                    ServeOrchestrator orchestrator = context.getBean(ServeOrchestrator.class);
                    assertThat(orchestrator).isInstanceOf(A2AEnabledServeOrchestrator.class);
                });
    }

    @Configuration
    static class MockOrchestratorConfig {
        /**
         * Provides a placeholder orchestrator bean for the test context.
         *
         * @return a minimal {@link A2AEnabledServeOrchestrator} instance
         */
        @Bean
        public A2AEnabledServeOrchestrator serveOrchestrator() {
            return new A2AEnabledServeOrchestrator(null, null, null, null, "agent", 1, 1, 1L);
        }
    }
}
