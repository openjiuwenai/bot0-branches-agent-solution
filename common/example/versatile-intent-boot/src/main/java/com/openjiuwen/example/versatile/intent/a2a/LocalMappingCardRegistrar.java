/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/**
 * Registers ephemeral {@link AgentCard}s from the configured
 * {@code openjiuwen.service.card-resolver.local-mapping} into
 * {@link A2ARemoteAgentCardRegistry} at startup.
 *
 * <p>Activated when at least one local-mapping entry is present. Each entry's
 * base URL is appended with {@code /a2a} to form the JSON-RPC endpoint URL.
 * This populates the registry that {@code DefaultRemoteAgentCaller} and
 * {@code DefaultCardResolver} read from, enabling cross-layer forwarding over
 * localhost HTTP without the A2A Gateway (L2 §5.5.3 方案 B).
 *
 * @since 0.1.0
 */
@Configuration
@EnableConfigurationProperties(LocalMappingProperties.class)
public class LocalMappingCardRegistrar {
    private static final Logger log = LoggerFactory.getLogger(LocalMappingCardRegistrar.class);
    private static final String JSONRPC_PATH = "/a2a";

    private final A2ARemoteAgentCardRegistry registry;
    private final LocalMappingProperties properties;

    public LocalMappingCardRegistrar(A2ARemoteAgentCardRegistry registry, LocalMappingProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    @PostConstruct
    void registerLocalMappings() {
        Map<String, String> mapping = properties.getLocalMapping();
        if (mapping == null || mapping.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            String agentCard = entry.getKey();
            String baseUrl = entry.getValue();
            if (agentCard == null || agentCard.isBlank() || baseUrl == null || baseUrl.isBlank()) {
                continue;
            }
            String jsonRpcUrl = baseUrl.endsWith("/") ? baseUrl + "a2a" : baseUrl + JSONRPC_PATH;
            AgentCard card = AgentCard.builder()
                    .name(agentCard)
                    .description("Local mapping for " + agentCard)
                    .version("0.1.0")
                    .url(jsonRpcUrl)
                    .capabilities(AgentCapabilities.builder().streaming(true).build())
                    .skills(List.of())
                    .defaultInputModes(List.of())
                    .defaultOutputModes(List.of())
                    .supportedInterfaces(List.of(
                            new AgentInterface("jsonrpc", jsonRpcUrl, null,
                                    AgentInterface.CURRENT_PROTOCOL_VERSION)))
                    .build();
            registry.register(agentCard, card);
            log.info("Registered local agentCard mapping: {} -> {}", agentCard, jsonRpcUrl);
        }
    }

    /**
     * Registers the local HTTP caller bean for 方案 B 联调.
     *
     * <p>Active under the {@code mock-versatile} profile <em>and only when the
     * A2A Gateway caller is disabled</em> ({@code openjiuwen.service.a2a-gateway.enabled}
     * is {@code false} or absent). When the gateway caller is enabled it takes
     * precedence, since both beans use {@code @ConditionalOnMissingBean} and
     * auto-configuration ordering alone is not enough to disambiguate them.
     *
     * <p>Overrides the runtime's {@code DefaultRemoteAgentCaller} (which hits
     * an A2A SDK ServiceLoader transport issue in Spring Boot fat jars). The
     * local caller posts directly to the target runtime's {@code /v1/query}
     * endpoint via plain HTTP.
     *
     * @param registry the remote agent card registry (populated by {@link LocalMappingCardRegistrar})
     * @return the local HTTP remote agent caller
     */
    @Bean
    @Profile("mock-versatile")
    @ConditionalOnProperty(prefix = "openjiuwen.service.a2a-gateway", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    @ConditionalOnMissingBean(RemoteAgentCaller.class)
    public RemoteAgentCaller localHttpRemoteAgentCaller(A2ARemoteAgentCardRegistry registry) {
        return new LocalHttpRemoteAgentCaller(registry);
    }
}
