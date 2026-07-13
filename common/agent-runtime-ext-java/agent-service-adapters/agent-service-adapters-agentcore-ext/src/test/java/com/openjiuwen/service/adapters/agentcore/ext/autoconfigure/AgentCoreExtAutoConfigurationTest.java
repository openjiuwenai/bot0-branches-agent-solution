/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests AgentCore extension auto-configuration wiring.
 *
 * @since 2026-06-30
 */
class AgentCoreExtAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentCoreExtAutoConfiguration.class))
            .withBean(A2ARemoteAgentCardRegistry.class);

    @Tag("smoke")
    @Test
    void createsInstallerWithoutCreatingAgentHandler() {
        runner.withPropertyValues("openjiuwen.service.handler=agentcore-ext")
                .run(context -> {
                    assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
                    assertThat(context).doesNotHaveBean(AgentHandler.class);
                });
    }

    @Test
    void createsInstallerWhenRemoteUrlIsMissing() {
        runner.withPropertyValues("openjiuwen.service.handler=agentcore-ext")
                .run(context -> {
                    assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
                    assertThat(context).doesNotHaveBean(AgentHandler.class);
                });
    }

    @Test
    void createsInstallerForPlainAgentcoreHandler() {
        runner.withPropertyValues("openjiuwen.service.handler=agentcore")
                .run(context -> {
                    assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
                    assertThat(context).doesNotHaveBean(AgentHandler.class);
                });
    }
}
