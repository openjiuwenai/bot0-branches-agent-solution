package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteA2aToolInstallerTest {

    @Test
    void installsRailToolCardIntoBaseAgentAbilityManagerWithoutDuplicating() throws IOException {
        try (RemoteA2aAgentCardCacheTest.CardServer server = RemoteA2aAgentCardCacheTest.CardServer.start(
                RemoteA2aAgentCardCacheTest.cardJson("Remote Agent", "Find hotels", "/a2a"))) {
            ReActAgent agent = reactAgent();
            RemoteA2aAgentCardCache cache = new RemoteA2aAgentCardCache(
                    RemoteA2aAgentCardCacheTest.properties(
                            RemoteA2aAgentCardCacheTest.agent(server.baseUrl(), "agent-b")),
                    new A2ARemoteAgentCardRegistry());
            RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(cache);

            installer.install(agent);
            installer.install(agent);

            assertThat(agent.getAbilityManager().listToolInfo())
                    .filteredOn(tool -> "agent-b".equals(tool.getName()))
                    .singleElement()
                    .satisfies(tool -> {
                        assertThat(tool.getDescription()).contains("Find hotels");
                        assertThat(tool.getParameters()).containsEntry("type", "object");
                    });
            assertThat(server.requests()).isEqualTo(1);
        }
    }

    @Test
    void skipsStringAgentIdMode() throws IOException {
        try (RemoteA2aAgentCardCacheTest.CardServer server = RemoteA2aAgentCardCacheTest.CardServer.start(
                RemoteA2aAgentCardCacheTest.cardJson("Remote Agent", "Find hotels", "/a2a"))) {
            RemoteA2aAgentCardCache cache = new RemoteA2aAgentCardCache(
                    RemoteA2aAgentCardCacheTest.properties(
                            RemoteA2aAgentCardCacheTest.agent(server.baseUrl(), "agent-b")),
                    new A2ARemoteAgentCardRegistry());
            RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(cache);

            installer.install("agent-id");

            assertThat(server.requests()).isZero();
            assertThat(cache.availableToolSpecs()).isEmpty();
        }
    }

    @Test
    void installsRailToolCardIntoDeepAgentInternalAbilityManager() throws IOException {
        try (RemoteA2aAgentCardCacheTest.CardServer server = RemoteA2aAgentCardCacheTest.CardServer.start(
                RemoteA2aAgentCardCacheTest.cardJson("Remote Agent", "Find hotels", "/a2a"))) {
            DeepAgent deepAgent = new DeepAgent(AgentCard.builder()
                    .id("deep-agent-a")
                    .name("Deep Agent A")
                    .description("Deep Agent A")
                    .build(), DeepAgentConfig.builder().enableTaskLoop(false).build(), null);
            RemoteA2aAgentCardCache cache = new RemoteA2aAgentCardCache(
                    RemoteA2aAgentCardCacheTest.properties(
                            RemoteA2aAgentCardCacheTest.agent(server.baseUrl(), "agent-b")),
                    new A2ARemoteAgentCardRegistry());
            RemoteA2aToolInstaller installer = RemoteA2aToolInstaller.create(cache);

            installer.install(deepAgent);

            assertThat(deepAgent.getAgent().getAbilityManager().listToolInfo())
                    .filteredOn(tool -> "agent-b".equals(tool.getName()))
                    .singleElement()
                    .satisfies(tool -> assertThat(tool.getDescription()).contains("Find hotels"));
        }
    }

    private static ReActAgent reactAgent() {
        return new ReActAgent(AgentCard.builder()
                .id("agent-a")
                .name("Agent A")
                .description("Agent A")
                .build());
    }
}
