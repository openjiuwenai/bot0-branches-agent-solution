/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.subagent;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.util.function.Supplier;

/**
 * SubAgent dispatcher — registers a subagent as a callable tool onto a
 * {@link ReActAgent}, following the AgentCard-as-tool pattern (Path A).
 *
 * <p>The subagent is registered via two steps (mirroring {@code ReplanTool.registerOnto}):
 * <ol>
 *   <li>{@code agent.getAbilityManager().add(toolCard)} — LLM visibility</li>
 *   <li>{@code Runner.resourceMgr().addTool(tool, tag)} — runtime dispatch</li>
 * </ol>
 *
 * <p>When the LLM calls the subagent tool, {@link SubAgentTool#invoke} delegates
 * to the injected {@link SubAgentExecutor}, which may spawn an in-process
 * subagent via {@code SpawnManager} or simply invoke another agent.
 *
 * <p><b>Honest boundary</b>: this is the simplest dispatch path. Context is NOT
 * isolated — the subagent shares the main agent's ModelContext. For full
 * isolation, use {@code InProcessSpawn} (Path B, deferred).
 *
 * @since 2026-07
 */
public final class SubAgentDispatcher {
    private SubAgentDispatcher() {
    }

    /**
     * Registers a subagent tool onto a ReActAgent (without user-input supplier).
     *
     * @param agent the host ReActAgent
     * @param toolName the tool name visible to the LLM
     * @param description the tool description for the LLM
     * @param executor the subagent execution logic
     * @return the registered SubAgentTool (for test assertions)
     */
    public static SubAgentTool registerOnto(ReActAgent agent, String toolName, String description,
            SubAgentExecutor executor) {
        return registerOnto(agent, toolName, description, executor, null);
    }

    /**
     * Registers a subagent tool onto a ReActAgent (with user-input supplier).
     *
     * <p>The supplier resolves the original user input so the subagent has full
     * context. Typically wired from {@code UserInputCaptureRail}'s shared reference.
     * When null or blank, falls back to the sub-goal (honest approximation).
     *
     * @param agent the host ReActAgent
     * @param toolName the tool name visible to the LLM
     * @param description the tool description for the LLM
     * @param executor the subagent execution logic
     * @param userInputSupplier supplies the original user input (nullable)
     * @return the registered SubAgentTool (for test assertions)
     */
    public static SubAgentTool registerOnto(ReActAgent agent, String toolName, String description,
            SubAgentExecutor executor, Supplier<String> userInputSupplier) {
        SubAgentTool tool = new SubAgentTool(toolName, description, executor, userInputSupplier);
        agent.getAbilityManager().add(tool.getCard());
        // C6: agent-scoped tag — provides attribution and aligns with the official
        // multi-agent demo pattern (addTool(tool, agentId)). Honest boundary: agent-core's
        // ResourceMgr dispatch is id-based (same-name tools still overwrite across agents);
        // the tag aids observability/attribution, not full isolation.
        Runner.resourceMgr().addTool(tool, agent.getCard().getId());
        return tool;
    }
}
