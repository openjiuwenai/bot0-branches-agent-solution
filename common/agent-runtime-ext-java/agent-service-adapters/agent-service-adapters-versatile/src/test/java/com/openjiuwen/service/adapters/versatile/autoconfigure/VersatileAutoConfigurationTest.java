/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.autoconfigure;

import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests Versatile auto-configuration wiring.
 *
 * @since 2026-06-30
 */
class VersatileAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(VersatileAutoConfiguration.class));

    @Test
    void onlyBindsPropertiesWhenConfigured() {
        runner.withPropertyValues(
                        "openjiuwen.service.versatile.url-template=https://example.test/{conversation_id}"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(VersatileProperties.class);
                    assertThat(context).doesNotHaveBean(AgentHandler.class);
                });
    }

    @Test
    void bindsVersatileConfigurationProperties() {
        runner.withPropertyValues(
                        "openjiuwen.service.versatile.url-template=https://example.test/{conversation_id}",
                        "openjiuwen.service.versatile.timeout=30s",
                        "openjiuwen.service.versatile.headers-template.Accept=text/event-stream",
                        "openjiuwen.service.versatile.forward-header-whitelist[0]=x-user-id",
                        "openjiuwen.service.versatile.result-node-name=AnswerNode",
                        "openjiuwen.service.versatile.endpoints[0].intent=booking",
                        "openjiuwen.service.versatile.endpoints[0].url-template="
                                + "https://example.test/booking/{conversation_id}"
                )
                .run(context -> {
                    VersatileProperties properties = context.getBean(VersatileProperties.class);

                    assertThat(properties.getUrlTemplate()).isEqualTo("https://example.test/{conversation_id}");
                    assertThat(properties.getTimeout()).hasSeconds(30);
                    assertThat(properties.getHeadersTemplate()).containsEntry("Accept", "text/event-stream");
                    assertThat(properties.getForwardHeaderWhitelist()).containsExactly("x-user-id");
                    assertThat(properties.getResultNodeName()).isEqualTo("AnswerNode");
                    assertThat(properties.getEndpoints()).hasSize(1);
                    assertThat(properties.getEndpoints().get(0).getIntent()).isEqualTo("booking");
                });
    }

    @Test
    void doesNotRegisterAgentHandlerForAnyHandlerType() {
        runner.withPropertyValues(
                        "openjiuwen.service.handler=versatile",
                        "openjiuwen.service.versatile.url-template=https://example.test/{conversation_id}"
                )
                .run(context -> assertThat(context).doesNotHaveBean(AgentHandler.class));
    }

    @Test
    void doesNotRegisterAgentHandlerWhenUrlTemplateIsMissing() {
        runner.withPropertyValues("openjiuwen.service.handler=versatile")
                .run(context -> assertThat(context).doesNotHaveBean(AgentHandler.class));
    }
}
