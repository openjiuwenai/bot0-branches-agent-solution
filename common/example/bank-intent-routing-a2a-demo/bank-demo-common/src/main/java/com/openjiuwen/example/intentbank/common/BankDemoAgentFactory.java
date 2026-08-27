/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;

import java.util.List;

/**
 * Creates consistently configured DeepAgent instances for the demo.
 *
 * @since 0.1.0
 */
public final class BankDemoAgentFactory {
    private BankDemoAgentFactory() {
    }

    /**
     * Creates a configured demo DeepAgent.
     *
     * @param definition
     *            static Agent definition
     * @param tools
     *            DeepAgent Tools
     * @param rails
     *            DeepAgent Rails
     * @param properties
     *            shared model properties
     *
     * @return configured DeepAgent
     */
    public static DeepAgent create(BankAgentDefinition definition, List<?> tools, List<?> rails,
            BankDemoProperties properties) {
        DeepAgentConfig config = DeepAgentConfig.builder().systemPrompt(definition.systemPrompt())
                .maxIterations(properties.getLlm().getMaxIterations()).enableTaskLoop(true)
                .enableTaskPlanning(definition.taskPlanning())
                .completionTimeout((double) properties.getLlm().getCompletionTimeout().toSeconds())
                .workspacePath(definition.workspacePath()).tools(List.copyOf(toObjects(tools)))
                .rails(List.copyOf(toObjects(rails))).model(properties.modelConfig())
                .backend(properties.backendConfig()).build();
        AgentCard card = AgentCard.builder().id(definition.id()).name(definition.name())
                .description(definition.description()).build();
        Workspace workspace = Workspace.builder().rootPath(definition.workspacePath()).language("zh-CN").build();
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    private static List<Object> toObjects(List<?> values) {
        return values == null ? List.of() : values.stream().map(value -> (Object) value).toList();
    }
}
