/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch;

import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.example.deepresearch.rail.AutoPersistMemoryRail;
import com.openjiuwen.example.deepresearch.rail.SandboxOps;
import com.openjiuwen.example.deepresearch.rail.SandboxRail;
import com.openjiuwen.example.deepresearch.rail.SkillObservationRail;
import com.openjiuwen.example.deepresearch.rail.SkillReadFileRail;
import com.openjiuwen.example.deepresearch.rail.UrlVerifyRail;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.MemoryRail;
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
 *
 * @since 2026-07-06
 */
public final class DeepResearchAgentFactory {
    private DeepResearchAgentFactory() {
    }

    /**
     * Builds the DeepAgent without sandbox rails.
     *
     * @param props the deep-research configuration
     * @return the configured {@link DeepAgent}
     */
    public static DeepAgent build(DeepResearchProperties props) {
        return build(props, null);
    }

    /**
     * Builds the DeepAgent, optionally wiring a {@link SandboxRail} when
     * {@code sandboxOpsSupplier} is non-null.
     *
     * <p>The supplier lets the runtime wrapper plug in an adapter around whatever
     * concrete sandbox client it wires (jiuwenbox / e2b / etc.) without leaking
     * runtime or core-java sandbox types into the library tier.
     *
     * @param props the deep-research configuration
     * @param sandboxOpsSupplier optional supplier of {@link SandboxOps}; {@code null}
     *     disables sandbox-backed rails
     * @return the configured {@link DeepAgent}
     */
    public static DeepAgent build(DeepResearchProperties props,
                                  Supplier<SandboxOps> sandboxOpsSupplier) {
        props.requireConfigured();

        List<Object> rails = buildRails(props, sandboxOpsSupplier);
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
        AgentCard card = AgentCard.builder()
                .id(props.getAgentId())
                .name(props.getAgentName())
                .description(props.getAgentDescription())
                .build();
        DeepAgent deepAgent = new DeepAgent(card, config, workspace);
        applySysOperationId(deepAgent, props.getSysOperationId());
        return deepAgent;
    }

    /**
     * Injects {@code sysOperationId} onto the inner ReActAgent's config after
     * DeepAgent construction. {@link DeepAgentConfig} does not carry this field,
     * so without this step {@code ReActAgent.registerSkill(...)} silently no-ops
     * (see {@code BaseAgent.lazyInitSkill}). Setting it enables the FEAT-005
     * SkillHub middleware to actually register downloaded skills onto the agent.
     *
     * @param deepAgent the freshly built agent whose inner ReActAgent config receives the id
     * @param sysOperationId FEAT-005 operation id; blank or {@code null} is treated as a no-op
     */
    private static void applySysOperationId(DeepAgent deepAgent, String sysOperationId) {
        if (sysOperationId == null || sysOperationId.isBlank()) {
            return;
        }
        Object inner = deepAgent.getAgent().getConfig();
        if (inner instanceof ReActAgentConfig reactConfig) {
            reactConfig.setSysOperationId(sysOperationId);
        }
    }

    private static List<Object> buildRails(DeepResearchProperties props,
                                           Supplier<SandboxOps> sandboxOpsSupplier) {
        List<Object> rails = new ArrayList<>();
        rails.add(new AutoPersistMemoryRail());
        if (props.getSkillDirectories() != null && !props.getSkillDirectories().isEmpty()) {
            rails.add(new SkillUseRail(props.getSkillDirectories(), props.getSkillMode()));
        }
        if (sandboxOpsSupplier != null) {
            rails.add(new SandboxRail(sandboxOpsSupplier, props.getWorkspacePath()));
            rails.add(new UrlVerifyRail(sandboxOpsSupplier));
        }
        rails.add(new SkillObservationRail());
        rails.add(new SkillReadFileRail(computeAllowedReadRoots(props)));
        return rails;
    }

    /**
     * Compose the allow-listed roots the demo's {@code read_file} rail may serve
     * files from. Always includes the workspace root; layers on any operator-
     * supplied {@code extra-readable-roots} (typically the FEAT-005 SkillHub
     * {@code localDir} so SKILL.md contents are readable by the LLM).
     *
     * @param props deep-research configuration whose workspace root and extra readable roots
     *     feed the allow-list
     * @return ordered list of allow-listed roots (workspace root first, then extras); may be empty
     */
    private static List<String> computeAllowedReadRoots(DeepResearchProperties props) {
        List<String> roots = new ArrayList<>();
        if (props.getWorkspacePath() != null && !props.getWorkspacePath().isBlank()) {
            roots.add(props.getWorkspacePath());
        }
        if (props.getExtraReadableRoots() != null) {
            roots.addAll(props.getExtraReadableRoots());
        }
        return roots;
    }
}
