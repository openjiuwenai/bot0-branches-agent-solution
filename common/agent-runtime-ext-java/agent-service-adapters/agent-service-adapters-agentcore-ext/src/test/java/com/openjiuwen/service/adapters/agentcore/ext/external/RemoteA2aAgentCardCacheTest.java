package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteA2aAgentCardCacheTest {

    @Test
    void refreshesPendingCardsRegistersRegistryAndBuildsToolSpec() throws IOException {
        try (CardServer server = CardServer.start(cardJson("Remote Agent", "Find hotels", "/a2a"))) {
            A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
            RemoteA2aAgentCardCache cache = new RemoteA2aAgentCardCache(
                    properties(agent(server.baseUrl(), "Agent B")),
                    registry);

            cache.refreshPendingOnce();

            assertThat(cache.availableToolSpecs()).singleElement().satisfies(spec -> {
                assertThat(spec.remoteAgentId()).isEqualTo("agent-b");
                assertThat(spec.toolName()).isEqualTo("agent-b");
                assertThat(spec.description()).contains("Find hotels");
                assertThat(spec.inputSchema()).containsEntry("type", "object");
            });
            assertThat(registry.resolveUrl("agent-b")).isEqualTo(server.baseUrl() + "/a2a");
            assertThat(server.requests).isEqualTo(1);
        }
    }

    @Test
    void readyCardsAreFrozenAfterSuccessfulRefresh() throws IOException {
        try (CardServer server = CardServer.start(cardJson("Remote Agent", "Skill", "/a2a"))) {
            RemoteA2aAgentCardCache cache = new RemoteA2aAgentCardCache(
                    properties(agent(server.baseUrl(), "Agent B")),
                    new A2ARemoteAgentCardRegistry());

            cache.refreshPendingOnce();
            cache.refreshPendingOnce();

            assertThat(server.requests).isEqualTo(1);
            assertThat(cache.availableToolSpecs()).extracting(RemoteA2aAgentCardCache.RemoteA2aToolSpec::toolName)
                    .containsExactly("agent-b");
        }
    }

    @Test
    void doesNotCreateToolForCardWithoutSkillsOrCallableInterface() throws IOException {
        try (CardServer noSkills = CardServer.start(cardJson("Remote Agent", null, "/a2a"));
             CardServer noInterface = CardServer.start(cardWithoutInterfaceJson())) {
            RemoteA2aAgentCardCache noSkillsCache = new RemoteA2aAgentCardCache(
                    properties(agent(noSkills.baseUrl(), "")),
                    new A2ARemoteAgentCardRegistry());
            RemoteA2aAgentCardCache noInterfaceCache = new RemoteA2aAgentCardCache(
                    properties(agent(noInterface.baseUrl(), "")),
                    new A2ARemoteAgentCardRegistry());

            noSkillsCache.refreshPendingOnce();
            noInterfaceCache.refreshPendingOnce();

            assertThat(noSkillsCache.availableToolSpecs()).isEmpty();
            assertThat(noInterfaceCache.availableToolSpecs()).isEmpty();
        }
    }

    static RemoteA2aProperties properties(RemoteA2aProperties.Agent... agents) {
        RemoteA2aProperties properties = new RemoteA2aProperties();
        RemoteA2aProperties.RemoteA2a remoteA2a = new RemoteA2aProperties.RemoteA2a();
        remoteA2a.setAgents(List.of(agents));
        properties.setRemoteA2a(remoteA2a);
        return properties;
    }

    static RemoteA2aProperties.Agent agent(String url, String name) {
        RemoteA2aProperties.Agent agent = new RemoteA2aProperties.Agent();
        agent.setUrl(url);
        agent.setName(name);
        return agent;
    }

    static String cardJson(String name, String skillDescription, String a2aPath) {
        String skills = skillDescription == null ? "[]" : """
                [{
                  "id":"workflow",
                  "name":"Workflow",
                  "description":"%s",
                  "tags":[]
                }]
                """.formatted(skillDescription);
        return """
                {
                  "name":"%s",
                  "description":"Remote card",
                  "version":"1.0.0",
                  "capabilities":{"streaming":true,"pushNotifications":false,"stateTransitionHistory":false,"extendedAgentCard":false},
                  "defaultInputModes":["text"],
                  "defaultOutputModes":["text"],
                  "skills":%s,
                  "supportedInterfaces":[{
                    "protocolBinding":"JSONRPC",
                    "url":"__BASE__%s"
                  }]
                }
                """.formatted(name, skills, a2aPath);
    }

    static String cardWithoutInterfaceJson() {
        return """
                {
                  "name":"Remote Agent",
                  "description":"Remote card",
                  "version":"1.0.0",
                  "capabilities":{"streaming":true,"pushNotifications":false,"stateTransitionHistory":false,"extendedAgentCard":false},
                  "defaultInputModes":["text"],
                  "defaultOutputModes":["text"],
                  "skills":[{
                    "id":"workflow",
                    "name":"Workflow",
                    "description":"Skill",
                    "tags":[]
                  }],
                  "supportedInterfaces":[]
                }
                """;
    }

    static final class CardServer implements AutoCloseable {
        private final HttpServer server;
        private final String baseUrl;
        private int requests;

        private CardServer(HttpServer server, String baseUrl) {
            this.server = server;
            this.baseUrl = baseUrl;
        }

        static CardServer start(String responseTemplate) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            CardServer cardServer = new CardServer(server, "http://127.0.0.1:" + server.getAddress().getPort());
            server.createContext("/.well-known/agent-card.json", exchange -> {
                cardServer.requests++;
                byte[] body = responseTemplate.replace("__BASE__", cardServer.baseUrl)
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return cardServer;
        }

        String baseUrl() {
            return baseUrl;
        }

        int requests() {
            return requests;
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
