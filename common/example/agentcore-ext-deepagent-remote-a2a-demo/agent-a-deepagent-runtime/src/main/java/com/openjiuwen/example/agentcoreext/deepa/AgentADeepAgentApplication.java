/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.deepa;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Agent A runtime. It delegates the first user request to Agent B through the
 * remote A2A tool injected by {@link JiuwenCoreAgentExtHandler}.
 *
 * @since 2026-07-03
 */
@SpringBootApplication
@EnableConfigurationProperties(DeepAgentLlmProperties.class)
public class AgentADeepAgentApplication {
    private static final String AGENT_ID = "agent-a-deepagent";

    public static void main(String[] args) {
        SpringApplication.run(AgentADeepAgentApplication.class, args);
    }

    @Bean
    AgentHandler deepAgentHandler(DeepAgentLlmProperties properties) {
        properties.requireConfigured();
        return new JiuwenCoreAgentExtHandler(buildDeepAgent(properties));
    }

    static DeepAgent buildDeepAgent(DeepAgentLlmProperties properties) {
        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(properties.getSystemPrompt())
                .maxIterations(properties.getMaxIterations())
                .enableTaskLoop(true)
                .completionTimeout((double) properties.getCompletionTimeout().toSeconds())
                .workspacePath(properties.getWorkspacePath())
                .model(properties.modelConfig())
                .backend(properties.backendConfig())
                .build();
        Workspace workspace = Workspace.builder()
                .rootPath(properties.getWorkspacePath())
                .language("zh-CN")
                .build();
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name("AgentADeepAgent")
                .description("DeepAgent runtime that delegates to remote Agent B through A2A")
                .build();
        return new DeepAgent(card, config, workspace);
    }
}
