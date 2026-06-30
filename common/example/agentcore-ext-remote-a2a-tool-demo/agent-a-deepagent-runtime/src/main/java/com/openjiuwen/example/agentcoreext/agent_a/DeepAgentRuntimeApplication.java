/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.agent_a;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.SkillUseRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.ext.agentfw.JiuwenCoreAgentExtHandler;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot application for the AgentCore extension DeepAgent demo runtime.
 *
 * @since 2026-06-30
 */
@SpringBootApplication
@EnableConfigurationProperties(DeepAgentLlmProperties.class)
public class DeepAgentRuntimeApplication {

    private static final String AGENT_ID = "agentcore-ext-deep-agent";

    public static void main(String[] args) {
        SpringApplication.run(DeepAgentRuntimeApplication.class, args);
    }

    @Bean
    AgentHandler deepAgentHandler(DeepAgentLlmProperties properties,
                                  ObjectProvider<MiddlewareAdapterRegistrar> registrar,
                                  RemoteA2aToolInstaller installer) {
        properties.requireConfigured();
        return new JiuwenCoreAgentExtHandler(buildDeepAgent(properties), registrar.getIfAvailable(), installer);
    }

    static DeepAgent buildDeepAgent(DeepAgentLlmProperties properties) {
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name("AgentCoreExtDeepAgent")
                .description("DeepAgent runtime with remote A2A tool injection")
                .build();
        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(properties.getSystemPrompt())
                .maxIterations(properties.getMaxIterations())
                .enableTaskLoop(true)
                .completionTimeout((double) properties.getCompletionTimeout().toSeconds())
                .workspacePath(properties.getWorkspacePath())
                .model(properties.modelConfig())
                .backend(properties.backendConfig())
                .skillDirectories(properties.getSkillDirectories())
                .skillMode(properties.getSkillMode())
                .rails(java.util.List.of(new SkillUseRail(
                        properties.getSkillDirectories(),
                        properties.getSkillMode())))
                .build();
        Workspace workspace = Workspace.builder()
                .rootPath(properties.getWorkspacePath())
                .language("zh-CN")
                .build();
        return new DeepAgent(card, config, workspace);
    }
}
