/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.explore;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ExploreToolRegistrar bearing tests.
 *
 * <p>Verifies dual registration (AbilityManager.add(card) +
 * Runner.resourceMgr().addTool(tool)) — the same contract as
 * {@code ReplanTool.registerOnto}.
 *
 * <p>mutation-RED:
 * <ul>
 *   <li>Strip AbilityManager.add → LLM can't see explore tool → RED</li>
 *   <li>Strip Runner.resourceMgr().addTool → tool can't be dispatched → RED</li>
 * </ul>
 *
 * @since 2026-07
 */
class ExploreToolRegistrarTest {
    @Test
    void registerOnto_makesExploreToolVisibleToLLM() {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test").build());
        AtomicReference<String> userInputRef = new AtomicReference<>("测试输入");
        Explorer explorer = (q, b) -> new ExplorationResult("findings", java.util.List.of("approach"));

        ExploreTool tool = ExploreToolRegistrar.registerOnto(agent, explorer, ExploreBudget.DEFAULT, userInputRef::get);

        var toolInfos = agent.getAbilityManager().listToolInfo();
        boolean hasExploreTool = toolInfos.stream().anyMatch(t -> "explore".equals(t.getName()));

        assertThat(hasExploreTool).as("AbilityManager.add(card) must make explore tool visible to LLM").isTrue();
    }

    @Test
    void registerOnto_toolInvokesExplorerWithSupplierValue() throws Exception {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test").build());
        AtomicReference<String> userInputRef = new AtomicReference<>("来自 supplier 的输入");
        ExplorationResult[] captured = new ExplorationResult[1];
        Explorer explorer = (q, b) -> {
            captured[0] = new ExplorationResult("explored: " + q);
            return captured[0];
        };

        ExploreTool tool = ExploreToolRegistrar.registerOnto(agent, explorer, ExploreBudget.DEFAULT, userInputRef::get);

        Object result = tool.invoke(Map.of(), Map.of());

        assertThat(result).isInstanceOf(Map.class);
        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertThat(resultMap.get("status")).isEqualTo("explored");
        assertThat(resultMap.get("findings")).isEqualTo("explored: 来自 supplier 的输入");
    }

    @Test
    void registerOnto_toolReturnsNoInputWhenSupplierNull() throws Exception {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name("test").build());
        AtomicReference<String> emptyRef = new AtomicReference<>();
        Explorer explorer = (q, b) -> new ExplorationResult("should not be called");

        ExploreTool tool = ExploreToolRegistrar.registerOnto(agent, explorer, ExploreBudget.DEFAULT, emptyRef::get);

        Object result = tool.invoke(Map.of(), Map.of());

        Map<?, ?> resultMap = (Map<?, ?>) result;
        assertThat(resultMap.get("status")).as("null supplier → no_input degradation (承重缺陷 D 场景)")
                .isEqualTo("no_input");
    }
}
