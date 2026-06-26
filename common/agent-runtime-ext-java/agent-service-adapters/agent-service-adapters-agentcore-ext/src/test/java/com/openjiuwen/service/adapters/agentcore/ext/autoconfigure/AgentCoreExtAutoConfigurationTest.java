package com.openjiuwen.service.adapters.agentcore.ext.autoconfigure;

import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aAgentCardCache;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aProperties;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AgentCoreExtAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AgentCoreExtAutoConfiguration.class))
            .withBean(A2ARemoteAgentCardRegistry.class);

    @Test
    void bindsPropertiesWithoutCreatingAgentHandler() {
        runner.withPropertyValues(
                        "openjiuwen.service.handler=agentcore-ext",
                        "openjiuwen.service.agentcore-ext.remote-a2a.agents[0].url=http://localhost:18091",
                        "openjiuwen.service.agentcore-ext.remote-a2a.agents[0].name=agent-b")
                .run(context -> {
                    assertThat(context).hasSingleBean(RemoteA2aProperties.class);
                    assertThat(context).hasSingleBean(RemoteA2aAgentCardCache.class);
                    assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
                    assertThat(context).doesNotHaveBean(AgentHandler.class);
                    assertThat(context.getBean(RemoteA2aProperties.class).getRemoteA2a().getAgents())
                            .singleElement()
                            .satisfies(agent -> {
                                assertThat(agent.getUrl()).isEqualTo("http://localhost:18091");
                                assertThat(agent.getName()).isEqualTo("agent-b");
                            });
                });
    }

    @Test
    void createsNoopInstallerWhenRemoteUrlIsMissing() {
        runner.withPropertyValues("openjiuwen.service.handler=agentcore-ext")
                .run(context -> {
                    assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
                    assertThat(context).doesNotHaveBean(RemoteA2aAgentCardCache.class);
                    assertThat(context).doesNotHaveBean(AgentHandler.class);
                });
    }

    @Test
    void doesNotCreateCacheForPlainAgentcoreHandler() {
        runner.withPropertyValues(
                        "openjiuwen.service.handler=agentcore",
                        "openjiuwen.service.agentcore-ext.remote-a2a.agents[0].url=http://localhost:18091")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(RemoteA2aAgentCardCache.class);
                    assertThat(context).hasSingleBean(RemoteA2aToolInstaller.class);
                    assertThat(context).doesNotHaveBean(AgentHandler.class);
                });
    }
}
