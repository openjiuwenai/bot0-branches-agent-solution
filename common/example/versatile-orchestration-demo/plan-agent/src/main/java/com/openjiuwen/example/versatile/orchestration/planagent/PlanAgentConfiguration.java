/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.example.versatile.orchestration.planagent.gateway.GatewayProtocolAdapter;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.app.custom.rest.CustomRestProtocolAdapter;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Assembles the ReActAgent plan-agent and wraps it in the agent-solution
 * agentcore-ext handler. The remote versatile-adapter agent is wired as an A2A
 * interrupt-rail tool via {@code openjiuwen.service.a2a.remote-agents} (handled
 * by AgentCoreExtAutoConfiguration / RemoteA2aToolInstaller).
 *
 * <p>The decomposition logic (intent values, {@code remoteInput} JSON shape, and
 * the per-turn execution rule) lives in the system prompt loaded from a
 * configurable classpath resource ({@link PlanAgentProperties#promptResource()},
 * default {@code prompts/plan-agent-system-prompt.md} = serial, one
 * versatile-adapter call per turn). Activate the {@code parallel-transfer}
 * profile to swap in {@code prompts/plan-agent-system-prompt-parallel.md}, which
 * runs balance queries serially first and then batches independent transfers
 * into a single turn for parallel dispatch. The skill subsystem is intentionally
 * NOT used (it does not load reliably at SUT runtime and is unnecessary for this
 * single-purpose planner).
 *
 * @since 2026-07-08
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PlanAgentProperties.class)
public class PlanAgentConfiguration {
    static final String AGENT_ID = "plan-agent";

    @Bean
    AgentHandler planAgentHandler(PlanAgentProperties props, ResourceLoader resourceLoader) {
        String systemPrompt = loadSystemPrompt(resourceLoader.getResource(props.promptResource()));
        return new JiuwenCoreAgentExtHandler(buildReActAgent(props, systemPrompt));
    }

    @Bean
    CustomRestProtocolAdapter gatewayProtocolAdapter(ObjectMapper objectMapper) {
        return new GatewayProtocolAdapter(objectMapper);
    }

    static ReActAgent buildReActAgent(PlanAgentProperties props, String systemPrompt) {
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name(AGENT_ID)
                .description("Plan agent that decomposes a banking request and calls versatile-adapter per task.")
                .build();
        ReActAgent agent = new ReActAgent(card);

        // Only the versatile-adapter A2A delegate tool is exposed (wired via
        // openjiuwen.service.a2a.remote-agents). No filesystem / shell / skill
        // tools — this agent only plans and delegates.
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", systemPrompt)))
                .maxIterations(12)
                .build()
                .configureModelClient(props.modelProvider(), props.apiKey(), props.apiBase(),
                        props.modelName(), props.sslVerify());
        ModelRequestConfig modelConfig = config.getModelConfigObj();
        modelConfig.setTemperature(0.0);
        modelConfig.setMaxTokens(props.maxTokens());
        agent.configure(config);

        return agent;
    }

    private static String loadSystemPrompt(Resource promptResource) {
        try {
            return promptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to load plan-agent system prompt from " + promptResource, e);
        }
    }
}
