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

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

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

    /**
     * Wiring graph contract, asserted for real (4-lens BLOCKER fix): the ReplanRail
     * passed to CriteriaReplanBridgeRail and the one registered on the agent MUST be
     * the SAME instance — violation = 2× replan budget (bridge counter + LLM
     * {@code __replan__} counter each get their own limit).
     *
     * <p>Access path: {@code getAgentCallbackManager()} (public API) → reflect the
     * {@code railRegistrations} map (private) for all registered rails; reflect the
     * bridge's private {@code replanRail} field; assertSame. mutation-RED (verified
     * 2026-08-15): change {@code agent.registerRail(sharedReplanRail)} to
     * {@code agent.registerRail(new ReplanRail(...))} → this test fails.
     *
     * @throws Exception if reflection into agent-core internals (railRegistrations /
     *                   the bridge's replanRail field) fails
     */
    @Test
    void registerOnto_sharedReplanRailIsSingleInstance() throws Exception {
        EdpaProperties props = new EdpaProperties();
        props.setMaxReplan(3);
        props.setCriteria(List.of("必须包含金额"));
        ReActAgent agent = newTestAgent();
        EdpaRails.registerOnto(agent, props, new RuleBasedCriteriaVerifier(), noopExplorer());

        Set<com.openjiuwen.core.singleagent.rail.AgentRail> rails = registeredRails(agent);
        ReplanRail replanRail = rails.stream().filter(r -> r instanceof ReplanRail)
                .map(r -> (ReplanRail) r).findFirst()
                .orElseThrow(() -> new AssertionError("no ReplanRail registered on agent"));
        CriteriaReplanBridgeRail bridge = rails.stream().filter(r -> r instanceof CriteriaReplanBridgeRail)
                .map(r -> (CriteriaReplanBridgeRail) r).findFirst()
                .orElseThrow(() -> new AssertionError("no CriteriaReplanBridgeRail registered on agent"));

        Field bridgeField = CriteriaReplanBridgeRail.class.getDeclaredField("replanRail");
        bridgeField.setAccessible(true);
        assertThat(bridgeField.get(bridge))
                .as("bridge's replan counter must be the SAME instance as the registered ReplanRail "
                        + "(violation = 2× replan budget)")
                .isSameAs(replanRail);
    }

    /**
     * Reflects the agent callback manager's rail registrations to enumerate all
     * rails registered on the agent.
     *
     * @param agent the agent assembled by registerOnto
     * @return all registered AgentRail instances
     * @throws Exception if reflection into agent-core internals fails
     */
    private static Set<com.openjiuwen.core.singleagent.rail.AgentRail> registeredRails(ReActAgent agent)
            throws Exception {
        var manager = agent.getAgentCallbackManager();
        Field regField = manager.getClass().getDeclaredField("railRegistrations");
        regField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var map = (java.util.Map<com.openjiuwen.core.singleagent.rail.AgentRail, ?>) regField.get(manager);
        return map.keySet();
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
