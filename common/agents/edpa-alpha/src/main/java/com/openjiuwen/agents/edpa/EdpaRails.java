/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa;

import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.ExploreToolRegistrar;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.edpa.rail.SteeringProvisionRail;
import com.openjiuwen.agents.edpa.rail.UserInputCaptureRail;
import com.openjiuwen.agents.edpa.verification.ProactiveConvergenceRail;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.core.singleagent.agents.ReActAgent;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Static facade for EDPA cognitive rail stack assembly — the sole truth source.
 *
 * <p>Extracted line-by-line from {@code EdpaAutoConfiguration}'s BeanPostProcessor
 * (C1/C2 refactor), naturally carrying the wiring-graph contract:
 * <ul>
 *   <li>{@code sharedReplanRail} is ONE instance shared by both the bridge rail
 *       and the LLM-driven {@code __replan__} dispatch — violation = 2× replan budget.</li>
 *   <li>{@code userInputRef} closure is shared across UserInputCaptureRail and
 *       ExploreTool (tool mode).</li>
 *   <li>{@code SteeringProvisionRail} binds the steering queue on the String-invoke
 *       path (issue-#13) — sole beforeInvoke override, runs before any consumer hook
 *       regardless of priority (agent-core sorts descending).</li>
 *   <li>Conditional branches: tool-vs-rail mode, criteria presence, convergence enable.</li>
 * </ul>
 *
 * <p><b>Usage (official demo pattern)</b>:
 * <pre>{@code
 * @Bean
 * AgentHandler myAgentHandler(LlmConfigResolver r) {
 *     ReActAgent agent = ExampleReActAgentFactory.build("my-agent", ..., llm);
 *     EdpaRails.registerOnto(agent, props, verifier, explorer);
 *     // MANDATORY observability bootstrap — EdpaRails does NOT install it.
 *     // Skipping this leaves forceFinish functional but UNOBSERVABLE
 *     // (no ForceFinishEvent ever fires — the e2e suite stepped on this).
 *     ReactRailsObservability.install(agent);
 *     return new JiuwenCoreAgentHandler(agent);
 * }
 * }</pre>
 *
 * @since 2026-08
 */
public final class EdpaRails {
    private EdpaRails() {
    }

    /**
     * Registers the EDPA cognitive rail stack onto a ReActAgent.
     *
     * <p><b>Honest boundary — same-JVM multi-agent (red-team finding, 2026-08-16)</b>:
     * ResourceMgr is a process-wide registry keyed by TOOL ID, and runtime dispatch looks
     * tools up by id (the tag on {@code addTool} is for attribution, not lookup isolation).
     * EDPA's cognitive tools use fixed ids ({@code explore}; react-rails {@code __replan__}
     * likewise), so TWO EDPA agents in one JVM overwrite each other's registrations
     * (last-writer-wins) — agent A's {@code explore} dispatch may execute agent B's
     * ExploreTool (reading B's captured user input). Single agent per JVM is safe; hosts
     * needing multiple EDPA agents must isolate at the process level until agent-core
     * dispatch supports tag-scoped lookup (frozen-layer cross-repo decision, deferred).
     *
 * <p>Assembly order (bearing — do not change without 4-lens):
     * <ol>
     *   <li>SteeringProvisionRail — binds steering queue (issue-#13). Sole beforeInvoke
     *       override, so it precedes all consumer hooks via hook isolation.</li>
     *   <li>UserInputCaptureRail + ExploreTool (tool mode) OR ExploreRail (rail mode).</li>
     *   <li>CriteriaReplanBridgeRail (+ ProactiveConvergenceRail if enabled) — shares
     *       the same ReplanRail instance as (4).</li>
     *   <li>ReplanRail + ReplanTool — shared budget counter.</li>
     *   <li>RootCauseRail — device-failure degrade.</li>
     * </ol>
     *
     * @param agent the host ReActAgent (not null)
     * @param properties EDPA configuration (exploreMode, exploreBudget, criteria, maxReplan, convergence)
     * @param criteriaVerifier the verifier for criteria gating
     * @param explorer the Explore-phase SPI
     * @return registration summary (for content-IFF assertions)
     */
    public static RegistrationSummary registerOnto(ReActAgent agent,
            com.openjiuwen.agents.edpa.autoconfigure.EdpaProperties properties,
            CriteriaVerifier criteriaVerifier, Explorer explorer) {
        // 1. Steering provision (issue-#13: invoke(taskString, null) never binds a queue;
        //    sole beforeInvoke override — hook isolation, not priority, orders it first).
        agent.registerRail(new SteeringProvisionRail());

        // 2. Explore phase — tool mode or rail mode.
        ExploreBudget budget = properties.toExploreBudget();
        boolean useToolMode = !"rail".equalsIgnoreCase(properties.getExploreMode());
        AtomicReference<String> userInputRef = new AtomicReference<>();
        if (useToolMode) {
            agent.registerRail(new UserInputCaptureRail(userInputRef));
            ExploreToolRegistrar.registerOnto(agent, explorer, budget,
                    () -> userInputRef.get());
        } else {
            // Rail mode: ExploreRail runs explore + injects findings via pushSteering
            // (no UserInputCaptureRail — ExploreTool is not registered in rail mode).
            agent.registerRail(new com.openjiuwen.agents.edpa.rail.ExploreRail(explorer, budget));
        }

        // 3. Verify gate — CriteriaReplanBridgeRail with SHARED replan budget.
        // Null-safe criteria (EdpaProperties.setCriteria documents "null or empty means skip").
        List<String> criteriaList = properties.getCriteria() != null
                ? properties.getCriteria() : List.of();
        ReplanRail sharedReplanRail = new ReplanRail(properties.getMaxReplan());
        if (!criteriaList.isEmpty()) {
            agent.registerRail(new CriteriaReplanBridgeRail(
                    criteriaVerifier, criteriaList, sharedReplanRail));
            if (properties.isProactiveConvergenceEnabled()) {
                agent.registerRail(new ProactiveConvergenceRail(
                        criteriaVerifier, criteriaList,
                        properties.getProactiveConvergenceStallWindow(),
                        ProactiveConvergenceRail.DEFAULT_COVERAGE_CRITICAL));
            }
        }

        // 4. Replan budget — register the SAME instance (shared with bridge above).
        if (properties.getMaxReplan() >= 0) {
            agent.registerRail(sharedReplanRail);
            ReplanTool.registerOnto(agent);
        }

        // 5. Device-failure degrade.
        agent.registerRail(new RootCauseRail());

        boolean convergenceRegistered = properties.isProactiveConvergenceEnabled()
                && !criteriaList.isEmpty();
        return new RegistrationSummary(useToolMode,
                criteriaList.isEmpty() ? 0 : 1,
                convergenceRegistered,
                properties.getMaxReplan());
    }

    /**
     * Lightweight registration record — enables content-IFF assertions on assembly.
     *
     * @param toolMode whether Explore was wired in tool mode (vs rail mode)
     * @param criteriaRailCount number of criteria-gated rails registered (0 or 1)
     * @param convergenceEnabled whether ProactiveConvergenceRail was registered
     * @param replanBudget the configured max replan count
     */
    public record RegistrationSummary(boolean toolMode, int criteriaRailCount,
            boolean convergenceEnabled, int replanBudget) {
    }
}
