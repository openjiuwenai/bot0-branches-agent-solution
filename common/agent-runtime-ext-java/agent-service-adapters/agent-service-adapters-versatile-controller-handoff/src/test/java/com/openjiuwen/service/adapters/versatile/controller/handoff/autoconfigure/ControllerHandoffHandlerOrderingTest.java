/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure;

import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffAgentHandler;
import com.openjiuwen.service.adapters.versatile.controller.handoff.ControllerHandoffExecutor;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.app.autoconfigure.AgentServiceAutoConfiguration;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.spi.AgentHandler;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression tests for the real auto-configuration ordering: the handler must claim
 * the {@code AgentHandler} slot before the runtime's {@code AgentServiceAutoConfiguration}
 * registers its {@code agentHandlerHolder} placeholder (both use
 * {@code @ConditionalOnMissingBean(AgentHandler.class)} — order decides the winner).
 * Discovered via the versatile-controller-handoff-demo deployment, where the
 * placeholder kept the instance permanently "agent not loaded".
 *
 * @since 2026-08-19
 */
class ControllerHandoffHandlerOrderingTest {

    private static final String[] ENABLED_PROPERTIES = {
            "openjiuwen.service.agent-id=agent_card_l1",
            "openjiuwen.service.versatile.url-template=http://127.0.0.1:1/conversations/{conversation_id}",
            "openjiuwen.service.versatile.handoff.enabled=true",
            "openjiuwen.service.versatile.handoff.classify.event-type=node_finished",
            "openjiuwen.service.versatile.handoff.classify.field-path=/data/node_name",
            "openjiuwen.service.versatile.handoff.classify.field-value=意图返回",
            "openjiuwen.service.versatile.handoff.fields.intent-id=/data/outputs/response"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AgentServiceAutoConfiguration.class,
                    A2AAutoConfiguration.class,
                    ControllerHandoffHandlerAutoConfiguration.class,
                    ControllerHandoffAutoConfiguration.class));

    @Test
    void handlerBeatsRuntimePlaceholderWhenEnabled() {
        runner.withPropertyValues(ENABLED_PROPERTIES)
                .withUserConfiguration(FakeCallerConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ControllerHandoffAgentHandler.class);
                    assertThat(context).hasSingleBean(AgentHandler.class);
                    assertThat(context).doesNotHaveBean("agentHandlerHolder");
                    assertThat(context).hasSingleBean(ControllerHandoffExecutor.class);
                });
    }

    @Test
    void placeholderServesWhenHandoffDisabled() {
        runner.withPropertyValues(
                        "openjiuwen.service.agent-id=agent_card_l1")
                .withUserConfiguration(FakeCallerConfiguration.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ControllerHandoffAgentHandler.class);
                    assertThat(context).hasBean("agentHandlerHolder");
                });
    }

    @Configuration
    static class FakeCallerConfiguration {
        @Bean
        RemoteAgentCaller fakeRemoteAgentCaller() {
            return new RemoteAgentCaller() {
                @Override
                public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call,
                        EventObserver eventObserver) {
                    return CompletableFuture.failedFuture(new IllegalStateException("not used"));
                }
            };
        }
    }
}
