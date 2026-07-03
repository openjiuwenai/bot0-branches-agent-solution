/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.deepb;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.interrupt.AskUserTool;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import java.util.List;

/**
 * Agent B runtime. It uses DeepAgent-native ask_user interruptions for the first
 * two rounds and returns the final answer on the third round.
 *
 * @since 2026-07-03
 */
@SpringBootApplication
@EnableConfigurationProperties(DeepAgentLlmProperties.class)
public class AgentBDeepAgentApplication {
    private static final String AGENT_ID = "agent-b-deepagent";

    public static void main(String[] args) {
        SpringApplication.run(AgentBDeepAgentApplication.class, args);
    }

    @Bean
    AgentHandler deepAgentHandler(DeepAgentLlmProperties properties) {
        properties.requireConfigured();
        return new JiuwenCoreAgentHandler(buildDeepAgent(properties));
    }

    static DeepAgent buildDeepAgent(DeepAgentLlmProperties properties) {
        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(properties.getSystemPrompt())
                .maxIterations(properties.getMaxIterations())
                .enableTaskLoop(true)
                .completionTimeout((double) properties.getCompletionTimeout().toSeconds())
                .workspacePath(properties.getWorkspacePath())
                .tools(List.of(new AskUserTool("cn")))
                .rails(List.of(new DemoAskUserRail()))
                .model(properties.modelConfig())
                .backend(properties.backendConfig())
                .build();
        Workspace workspace = Workspace.builder()
                .rootPath(properties.getWorkspacePath())
                .language("zh-CN")
                .build();
        AgentCard card = AgentCard.builder()
                .id(AGENT_ID)
                .name("AgentBDeepAgent")
                .description("DeepAgent runtime that interrupts twice and completes on the third round")
                .build();
        return new DeepAgent(card, config, workspace);
    }
}
