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

/** Creates consistently configured DeepAgent instances for the demo. */
public final class BankDemoAgentFactory {
    private BankDemoAgentFactory() {
    }

    public static DeepAgent create(String id, String name, String description, String systemPrompt,
            String workspacePath, boolean taskPlanning, List<?> tools, List<?> rails, BankDemoProperties properties) {
        DeepAgentConfig config = DeepAgentConfig.builder().systemPrompt(systemPrompt)
                .maxIterations(properties.getLlm().getMaxIterations()).enableTaskLoop(true)
                .enableTaskPlanning(taskPlanning)
                .completionTimeout((double) properties.getLlm().getCompletionTimeout().toSeconds())
                .workspacePath(workspacePath).tools(List.copyOf(toObjects(tools))).rails(List.copyOf(toObjects(rails)))
                .model(properties.modelConfig()).backend(properties.backendConfig()).build();
        AgentCard card = AgentCard.builder().id(id).name(name).description(description).build();
        Workspace workspace = Workspace.builder().rootPath(workspacePath).language("zh-CN").build();
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    private static List<Object> toObjects(List<?> values) {
        return values == null ? List.of() : values.stream().map(value -> (Object) value).toList();
    }
}
