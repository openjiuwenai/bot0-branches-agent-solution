/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.subagent;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * SubAgent dispatcher — registers a subagent as a callable tool onto a
 * {@link ReActAgent}, following the AgentCard-as-tool pattern (Path A).
 *
 * <p>The subagent is registered via two steps (mirroring {@code ReplanTool.registerOnto}):
 * <ol>
 *   <li>{@code agent.getAbilityManager().add(toolCard)} — LLM visibility</li>
 *   <li>{@code Runner.resourceMgr().addTool(tool, null)} — runtime dispatch</li>
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
     * Registers a subagent tool onto a ReActAgent.
     *
     * <p>The tool name becomes visible to the LLM. When called, it delegates to
     * the provided {@link SubAgentExecutor}.
     *
     * @param agent the host ReActAgent
     * @param toolName the tool name visible to the LLM
     * @param description the tool description for the LLM
     * @param executor the subagent execution logic
     * @return the registered SubAgentTool (for test assertions)
     */
    public static SubAgentTool registerOnto(ReActAgent agent, String toolName, String description,
            SubAgentExecutor executor) {
        SubAgentTool tool = new SubAgentTool(toolName, description, executor);
        agent.getAbilityManager().add(tool.getCard());
        Runner.resourceMgr().addTool(tool, null);
        return tool;
    }

    /**
     * Functional interface for subagent execution logic.
     *
     * <p>Implementations may:
     * <ul>
     *   <li>Invoke another agent directly (in-process, shared context)</li>
     *   <li>Spawn a subagent via {@code SpawnManager} (isolated context, deferred)</li>
     *   <li>Call an LLM with a subagent-specific prompt</li>
     * </ul>
     */
    @FunctionalInterface
    public interface SubAgentExecutor {
        /**
         * Execute the subagent task.
         *
         * @param userInput the original user input
         * @param subGoal the sub-goal assigned to this subagent
         * @return the subagent's result text
         */
        String execute(String userInput, String subGoal);
    }

    /**
     * Tool that wraps a {@link SubAgentExecutor} for LLM-callable dispatch.
     *
     * <p>Registration contract mirrors {@code ReplanTool}: dual registration
     * (AbilityManager.add(card) + Runner.resourceMgr().addTool(tool)).
     */
    public static class SubAgentTool extends Tool {
        private final SubAgentExecutor executor;

        private final ToolCard card;

        /**
         * Constructs the subagent tool.
         *
         * @param name tool name (also used as id)
         * @param description tool description for the LLM
         * @param executor subagent execution logic
         */
        public SubAgentTool(String name, String description, SubAgentExecutor executor) {
            super(ToolCard.builder().id(name).name(name).description(description)
                    .inputParams(Map.of("type", "object", "properties",
                            Map.of("sub_goal", Map.of("type", "string", "description", "子任务目标")), "required",
                            List.of("sub_goal")))
                    .build());
            this.executor = executor;
            this.card = super.getCard();
        }

        @Override
        public ToolCard getCard() {
            return card;
        }

        @Override
        public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
            String subGoal = args != null ? String.valueOf(args.getOrDefault("sub_goal", "")) : "";
            String userInput = args != null ? String.valueOf(args.getOrDefault("user_input", "")) : "";
            String result = executor.execute(userInput, subGoal);
            return Map.of("status", "completed", "result", result);
        }

        @Override
        public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
            return List.<Object>of(invoke(args, kwargs)).iterator();
        }
    }
}
