/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.intent.api.IntentSuite;
import com.openjiuwen.agents.intent.spi.IntentMatcher;
import com.openjiuwen.core.retrieval.reranker.Reranker;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.IntentDeepAgentInstaller;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.RuntimeIntentCatalogCoordinator;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Tests AgentCore extension auto-configuration wiring.
 *
 * @since 2026-06-30
 */
class AgentCoreExtAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(
                    AutoConfigurations.of(AgentCoreExtAutoConfiguration.class, IntentAutoConfiguration.class))
            .withBean(A2ARemoteAgentCardRegistry.class);

    @Tag("smoke")
    @Test
    void createsInstallerWithoutCreatingAgentHandler() {
        runner.withPropertyValues("openjiuwen.service.handler=agentcore-ext").run(context -> {
            assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
            assertThat(context).doesNotHaveBean(AgentHandler.class);
        });
    }

    @Test
    void createsInstallerWhenRemoteUrlIsMissing() {
        runner.withPropertyValues("openjiuwen.service.handler=agentcore-ext").run(context -> {
            assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
            assertThat(context).doesNotHaveBean(AgentHandler.class);
        });
    }

    @Test
    void createsInstallerForPlainAgentcoreHandler() {
        runner.withPropertyValues("openjiuwen.service.handler=agentcore").run(context -> {
            assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
            assertThat(context).doesNotHaveBean(AgentHandler.class);
        });
    }

    @Test
    void intentBeansAreDisabledByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(IntentSuite.class);
            assertThat(context).doesNotHaveBean(IntentDeepAgentInstaller.class);
            assertThat(context).doesNotHaveBean(RuntimeIntentProperties.class);
        });
    }

    @Test
    void createsOneIntentSuiteAndInstallerWhenEnabled() {
        runner.withPropertyValues("openjiuwen.service.intent.enabled=true",
                "openjiuwen.service.intent.match.threshold=0.72",
                "openjiuwen.service.intent.expose-agent-card-tools=false")
                .withBean(Reranker.class, () -> (query, candidates, topK) -> java.util.List.of()).run(context -> {
                    assertThat(context).hasSingleBean(IntentSuite.class);
                    assertThat(context).hasSingleBean(RuntimeIntentCatalogCoordinator.class);
                    assertThat(context).hasSingleBean(IntentDeepAgentInstaller.class);
                    assertThat(context.getBean(IntentSuite.class).config().matchThreshold()).isEqualTo(0.72D);
                    assertThat(context.getBean(IntentDeepAgentInstaller.class).exposeAgentCardTools()).isFalse();
                });
    }

    @Test
    void customMatcherBeanDoesNotRequireReranker() {
        IntentMatcher matcher = context -> java.util.Optional.empty();
        runner.withPropertyValues("openjiuwen.service.intent.enabled=true").withBean(IntentMatcher.class, () -> matcher)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(IntentMatcher.class)).isSameAs(matcher);
                    assertThat(context).doesNotHaveBean(Reranker.class);
                });
    }

    @Test
    void discoversIntentConfigurationFromAutoConfigurationImports() {
        new ApplicationContextRunner().withUserConfiguration(AutoConfigurationImportsApplication.class)
                .withBean(A2ARemoteAgentCardRegistry.class)
                .withBean(Reranker.class, () -> (query, candidates, topK) -> java.util.List.of())
                .withPropertyValues("openjiuwen.service.intent.enabled=true").run(context -> {
                    assertThat(context).hasSingleBean(IntentSuite.class);
                    assertThat(context).hasSingleBean(IntentDeepAgentInstaller.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfigurationImportsApplication {
    }
}
