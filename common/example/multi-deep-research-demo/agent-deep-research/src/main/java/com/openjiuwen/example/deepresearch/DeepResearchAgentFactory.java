/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.example.deepresearch.rail.AutoPersistMemoryRail;
import com.openjiuwen.example.deepresearch.rail.SandboxOps;
import com.openjiuwen.example.deepresearch.rail.SandboxRail;
import com.openjiuwen.example.deepresearch.rail.UrlVerifyRail;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.SkillUseRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Library-tier factory: turn {@link DeepResearchProperties} into a configured {@link DeepAgent}.
 *
 * <p>Depends only on {@code agent-core-java}. No Spring, no runtime SAM glue — the wrapper
 * module is responsible for plugging this {@link DeepAgent} into the {@code AgentHandler} SPI.
 *
 * <p>Long-term memory is provided by harness's built-in {@link MemoryRail}, which auto-registers
 * the {@code write_memory / read_memory / memory_search / memory_get / edit_memory} tools on the
 * DeepAgent and injects the corresponding prompt section. The rail stores memory files under
 * {@link Workspace#getNodePath(String)} for section {@code "memory"}.
 */
public final class DeepResearchAgentFactory {

    private DeepResearchAgentFactory() {
    }

    public static DeepAgent build(DeepResearchProperties props) {
        return build(props, null);
    }

    /**
     * Build the DeepAgent, optionally wiring a {@link SandboxRail} when
     * {@code sandboxOpsSupplier} is non-null. The supplier lets the runtime wrapper
     * plug in an adapter around whatever concrete sandbox client it wires
     * (jiuwenbox / e2b / etc.) without leaking runtime or core-java sandbox
     * types into the library tier.
     */
    public static DeepAgent build(DeepResearchProperties props,
                                  Supplier<SandboxOps> sandboxOpsSupplier) {
        props.requireConfigured();

        AgentCard card = AgentCard.builder()
                .id(props.getAgentId())
                .name(props.getAgentName())
                .description(props.getAgentDescription())
                .build();

        List<Object> rails = new ArrayList<>();
        rails.add(new AutoPersistMemoryRail());
        if (props.getSkillDirectories() != null && !props.getSkillDirectories().isEmpty()) {
            rails.add(new SkillUseRail(props.getSkillDirectories(), props.getSkillMode()));
        }
        if (sandboxOpsSupplier != null) {
            rails.add(new SandboxRail(sandboxOpsSupplier, props.getWorkspacePath()));
            rails.add(new UrlVerifyRail(sandboxOpsSupplier));
        }

        DeepAgentConfig config = DeepAgentConfig.builder()
                .systemPrompt(props.getSystemPrompt())
                .maxIterations(props.getMaxIterations())
                .enableTaskLoop(props.isEnableTaskLoop())
                .completionTimeout((double) props.getCompletionTimeout().toSeconds())
                .workspacePath(props.getWorkspacePath())
                .model(props.modelConfig())
                .backend(props.backendConfig())
                .skillDirectories(props.getSkillDirectories())
                .skillMode(props.getSkillMode())
                .rails(rails)
                .build();

        Workspace workspace = Workspace.builder()
                .rootPath(props.getWorkspacePath())
                .language(props.getWorkspaceLanguage())
                .build();

        return new DeepAgent(card, config, workspace);
    }
}
