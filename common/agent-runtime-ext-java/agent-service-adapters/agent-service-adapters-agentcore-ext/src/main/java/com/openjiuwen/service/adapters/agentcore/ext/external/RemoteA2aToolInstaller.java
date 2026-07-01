/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Installs remote A2A tool rails into supported AgentCore agents.
 *
 * @since 2026-06-30
 */
public class RemoteA2aToolInstaller {
    private static final Logger log = LoggerFactory.getLogger(RemoteA2aToolInstaller.class);
    private static final Map<String, Object> INPUT_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "remoteInput", Map.of(
                            "type", "string",
                            "description", "Text to send as the remote A2A user message.")),
            "required", List.of("remoteInput"),
            "additionalProperties", true);

    private final A2ARemoteAgentCardRegistry registry;
    private final Map<Object, Set<String>> installedRemoteAgentNames = Collections.synchronizedMap(new WeakHashMap<>());

    private RemoteA2aToolInstaller(A2ARemoteAgentCardRegistry registry) {
        this.registry = registry;
    }

    /**
     * Creates a remote A2A tool installer.
     *
     * @param registry remote A2A card registry
     * @return remote A2A tool installer
     *
     * @since 2026-06-30
     */
    public static RemoteA2aToolInstaller create(A2ARemoteAgentCardRegistry registry) {
        return new RemoteA2aToolInstaller(registry);
    }

    /**
     * Installs remote A2A tools into the supplied agent when supported.
     *
     * @param agent target agent instance
     *
     * @since 2026-06-30
     */
    public void install(Object agent) {
        if (registry == null) {
            return;
        }
        if (agent instanceof String) {
            log.info("agent-id mode cannot install remote A2A tools in v1");
            return;
        }
        BaseAgent target = resolveBaseAgent(agent);
        if (target == null) {
            log.warn("Unsupported agent type for remote A2A tool install: {}",
                    agent == null ? "null" : agent.getClass().getName());
            return;
        }
        Set<String> installedNames = installedRemoteAgentNames.computeIfAbsent(target, key -> new LinkedHashSet<>());
        synchronized (installedNames) {
            List<RemoteA2aToolSpec> newSpecs = registry.getAll().stream()
                    .filter(entry -> !installedNames.contains(entry.name()))
                    .map(RemoteA2aToolInstaller::toSpec)
                    .flatMap(java.util.Optional::stream)
                    .toList();
            if (newSpecs.isEmpty()) {
                return;
            }
            RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(newSpecs);
            target.registerRail(rail);
            newSpecs.forEach(spec -> installedNames.add(spec.remoteAgentId()));
            log.info("Installed remote A2A interrupt rail rail={} targetAgent={} tools={} toolDetails={}",
                    rail.getClass().getSimpleName(),
                    target.getClass().getName(),
                    newSpecs.stream().map(RemoteA2aToolSpec::toolName).toList(),
                    newSpecs);
        }
    }

    private static java.util.Optional<RemoteA2aToolSpec> toSpec(A2ARemoteAgentCardRegistry.RemoteAgentEntry entry) {
        String toolName = validToolName(entry.name());
        if (toolName == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new RemoteA2aToolSpec(toolName, toolName, description(toolName, entry.card()),
                INPUT_SCHEMA));
    }

    private static String validToolName(String remoteAgentName) {
        if (remoteAgentName == null || remoteAgentName.isBlank()) {
            log.warn("Remote A2A agent name is blank, skip tool injection");
            return null;
        }
        return remoteAgentName;
    }

    private static String description(String remoteAgentName, AgentCard card) {
        String skillDescriptions = skillDescriptions(card);
        if (!skillDescriptions.isBlank()) {
            return skillDescriptions;
        }
        if (card != null && card.description() != null && !card.description().isBlank()) {
            return card.description().trim();
        }
        return "Delegate this request to remote A2A agent '" + remoteAgentName + "'.";
    }

    private static String skillDescriptions(AgentCard card) {
        if (card == null || card.skills() == null || card.skills().isEmpty()) {
            return "";
        }
        return card.skills().stream()
                .map(AgentSkill::description)
                .filter(description -> description != null && !description.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static BaseAgent resolveBaseAgent(Object agent) {
        if (agent instanceof BaseAgent baseAgent) {
            return baseAgent;
        }
        if (agent instanceof DeepAgent deepAgent) {
            return deepAgent.getAgent();
        }
        return null;
    }

    public record RemoteA2aToolSpec(String remoteAgentId, String toolName, String description,
                                    Map<String, Object> inputSchema) {
    }
}
