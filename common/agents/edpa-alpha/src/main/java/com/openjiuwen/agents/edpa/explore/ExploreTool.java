/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Virtual tool the LLM calls to trigger active exploration.
 *
 * <p>Mirrors {@code ReplanTool}'s registration contract (see react-rails): the card is
 * surfaced to the LLM via {@code AbilityManager.add(card)} (auto-registered by
 * {@code AgentCallbackManager.registerRail} when the owning rail returns it from
 * {@code getTools()}), and the executable is registered into
 * {@code Runner.resourceMgr()} (dispatch) by {@link ExploreRail#init}.
 *
 * <p><b>id==name invariant</b>: {@code executeSingleToolCall} resolves the executable key
 * as {@code card.getId()} (fallback {@code card.getName()}); {@code ResourceMgr.addTool}
 * stores it under {@code card.getId()} (auto-generated UUID when null). The two keys match
 * only when {@code id} is set explicitly — hence the card sets {@code .id(TOOL_NAME)}.
 *
 * <p>The tool holds a {@link Supplier} for the cached user query (captured by
 * {@link ExploreRail} in {@code beforeModelCall} from {@code InvokeInputs.getQuery()}),
 * because {@link Tool#invoke} receives only tool-call arguments, not the original user
 * input.
 *
 * @since 2026-07
 */
public class ExploreTool extends Tool {
    /** Tool name visible to the LLM. */
    public static final String TOOL_NAME = "explore";

    /** Argument key for optional exploration focus hint. */
    public static final String ARG_FOCUS = "focus";

    /** Result-map key carrying the exploration findings string. */
    public static final String RESULT_FINDINGS = "findings";

    /** Result-map key carrying the candidate approaches list. */
    public static final String RESULT_CANDIDATES = "candidateApproaches";

    /** Result-map key carrying the status string. */
    public static final String RESULT_STATUS = "status";

    static final ToolCard CARD = ToolCard.builder().id(TOOL_NAME).name(TOOL_NAME)
            .description("主动探索问题空间，收集信息与候选方案。在规划前调用以扩展对问题的理解。" + "参数：focus（可选，探索聚焦方向）。")
            .inputParams(Map.of("type", "object", "properties",
                    Map.of(ARG_FOCUS, Map.of("type", "string", "description", "探索聚焦方向（可选）")), "required", List.of()))
            .build();

    private final Explorer explorer;
    private final ExploreBudget budget;
    private final Supplier<String> userInputSupplier;

    /**
     * Constructs the explore tool.
     *
     * @param explorer the Explorer SPI implementation
     * @param budget exploration budget
     * @param userInputSupplier supplies the cached user query (set by ExploreRail.beforeModelCall)
     */
    public ExploreTool(Explorer explorer, ExploreBudget budget, Supplier<String> userInputSupplier) {
        super(CARD);
        this.explorer = explorer;
        this.budget = budget;
        this.userInputSupplier = userInputSupplier;
    }

    @Override
    public ToolCard getCard() {
        return CARD;
    }

    @Override
    public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) throws Exception {
        String userInput = userInputSupplier.get();
        if (userInput == null || userInput.isEmpty()) {
            // No query captured yet — degrade honestly rather than explore empty space.
            return Map.of(RESULT_STATUS, "no_input", "message", "尚无用户问题输入，无法探索。请先明确要解决的问题。");
        }
        ExplorationResult result = explorer.explore(userInput, budget);
        return Map.of(RESULT_STATUS, "explored", RESULT_FINDINGS, result.findings(), RESULT_CANDIDATES,
                result.candidateApproaches());
    }

    @Override
    public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) throws Exception {
        return List.<Object>of(invoke(args, kwargs)).iterator();
    }
}
