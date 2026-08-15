/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.EdpaRails;
import com.openjiuwen.agents.edpa.explore.ExplorationResult;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * EdpaRails.registerOnto bearing tests — assembly truth source (C1/C2 refactor).
 *
 * <p>Tests the wiring graph contract:
 * <ul>
 *   <li>sharedReplanRail = ONE instance shared between bridge rail and ReplanRail registration.</li>
 *   <li>SteeringProvisionRail registered FIRST (issue-#13).</li>
 *   <li>Tool mode vs rail mode conditional.</li>
 *   <li>Criteria + convergence conditional.</li>
 * </ul>
 *
 * @since 2026-08
 */
class EdpaAutoConfigurationTest {

    private static Explorer noopExplorer() {
        return (userInput, budget) -> new ExplorationResult("test");
    }

    @Test
    void registerOnto_returnsSummaryWithToolModeDefault() {
        EdpaProperties props = new EdpaProperties();
        var summary = EdpaRails.registerOnto(newTestAgent(), props,
                new RuleBasedCriteriaVerifier(), noopExplorer());

        assertThat(summary.toolMode()).as("default exploreMode is tool (not rail)").isTrue();
        assertThat(summary.criteriaRailCount()).as("empty criteria → no bridge rail").isEqualTo(0);
        assertThat(summary.convergenceEnabled()).as("convergence disabled by default").isFalse();
    }

    @Test
    void registerOnto_railModeSetsSummaryFalse() {
        EdpaProperties props = new EdpaProperties();
        props.setExploreMode("rail");
        var summary = EdpaRails.registerOnto(newTestAgent(), props,
                new RuleBasedCriteriaVerifier(), noopExplorer());

        assertThat(summary.toolMode()).as("exploreMode=rail → toolMode=false").isFalse();
    }

    @Test
    void registerOnto_criteriaNonEmptyRegistersBridgeRail() {
        EdpaProperties props = new EdpaProperties();
        props.setCriteria(List.of("必须包含金额"));
        ReActAgent agent = newTestAgent();
        var summary = EdpaRails.registerOnto(agent, props,
                new RuleBasedCriteriaVerifier(), noopExplorer());

        assertThat(summary.criteriaRailCount()).as("non-empty criteria → 1 bridge rail").isEqualTo(1);
    }

    @Test
    void registerOnto_registersReplanTool() {
        EdpaProperties props = new EdpaProperties();
        props.setMaxReplan(2);
        ReActAgent agent = newTestAgent();
        EdpaRails.registerOnto(agent, props,
                new RuleBasedCriteriaVerifier(), noopExplorer());

        var toolInfos = agent.getAbilityManager().listToolInfo();
        boolean hasReplanTool = toolInfos.stream().anyMatch(t -> "__replan__".equals(t.getName()));
        assertThat(hasReplanTool).as("ReplanTool.registerOnto must make __replan__ visible to the LLM").isTrue();
    }

    @Test
    void registerOnto_sharedReplanRailWiringDocumented() {
        // Wiring graph contract (documented, not directly assertable — BaseAgent has
        // no rail listing API): the ReplanRail passed to CriteriaReplanBridgeRail and
        // the one registered on the agent MUST be the same instance. Violation = 2×
        // replan budget (4-lens MAJOR #1). EdpaRails.registerOnto enforces this by
        // constructing ONE sharedReplanRail variable and passing it to both.
        // mutation-RED: change `agent.registerRail(sharedReplanRail)` to
        // `agent.registerRail(new ReplanRail(...))` → bridge and agent counters diverge
        // → e2e replan budget assertion (2× budget) catches the bug.
        EdpaProperties props = new EdpaProperties();
        props.setMaxReplan(3);
        props.setCriteria(List.of("必须包含金额"));
        var summary = EdpaRails.registerOnto(newTestAgent(), props,
                new RuleBasedCriteriaVerifier(), noopExplorer());

        assertThat(summary.replanBudget()).as("summary must reflect configured budget").isEqualTo(3);
        assertThat(summary.criteriaRailCount()).as("bridge registered with shared budget").isEqualTo(1);
    }

    @Test
    void defaultCriteriaVerifierIsRuleBased() {
        // C5: default verifier bean must be RuleBasedCriteriaVerifier (not GroundTruthVerifier
        // with empty checkers which would be 100% keyword anyway but name-suggests deterministic).
        CriteriaVerifier defaultVerifier = new EdpaAutoConfiguration().edpaCriteriaVerifier();
        assertThat(defaultVerifier).as("C5: default must be RuleBasedCriteriaVerifier")
                .isInstanceOf(RuleBasedCriteriaVerifier.class);
    }

    private static ReActAgent newTestAgent() {
        return new ReActAgent(AgentCard.builder().name("test-agent").build());
    }
}
