/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.util.function.Supplier;

/**
 * Registers {@link ExploreTool} onto a {@link ReActAgent} — the tool-driven
 * Explore activation path (Species E, GEPA-converged).
 *
 * <p>Mirrors {@code ReplanTool.registerOnto}'s dual-registration contract:
 * <ol>
 *   <li>{@code agent.getAbilityManager().add(card)} — LLM visibility (listToolInfo)</li>
 *   <li>{@code Runner.resourceMgr().addTool(tool, null)} — runtime dispatch</li>
 * </ol>
 *
 * <p><b>承重缺陷 D 治本</b>: ExploreTool.invoke needs userInput from a
 * {@link Supplier}. The supplier is filled by {@link UserInputCaptureRail}
 * (registered separately). This registrar creates the tool with a shared
 * supplier reference — both the tool and the capture rail write to the same
 * {@link Supplier}, ensuring ExploreTool always has the query when the LLM
 * calls it.
 *
 * @since 2026-07
 */
public final class ExploreToolRegistrar {
    private ExploreToolRegistrar() {
    }

    /**
     * Registers an ExploreTool onto the agent, backed by the given Explorer
     * and a shared userInput supplier.
     *
     * @param agent the target ReActAgent
     * @param explorer the Explorer SPI implementation
     * @param budget exploration budget
     * @param userInputSupplier shared supplier (filled by UserInputCaptureRail)
     * @return the registered ExploreTool (for test assertions)
     */
    public static ExploreTool registerOnto(ReActAgent agent, Explorer explorer, ExploreBudget budget,
            Supplier<String> userInputSupplier) {
        ExploreTool tool = new ExploreTool(explorer, budget, userInputSupplier);
        ToolCard card = tool.getCard();
        agent.getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
        return tool;
    }
}
