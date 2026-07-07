/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.trace;

import com.openjiuwen.agents.reactrails.replan.PevReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.GradientVerifier;
import com.openjiuwen.agents.reactrails.verification.MutableCriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.prompts.PromptSection;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * E-hill-climbing-trace-feedback orchestrator.
 *
 * <p>Wraps a {@link ReActAgent} factory and runs iterative invoke-analyze-adjust
 * cycles. Each iteration:
 * <ol>
 *   <li>Builds an agent with the current best-known configuration
 *       (criteria list, verifier type, prompt sections, maxReplan).</li>
 *   <li>Registers a {@link TraceCollectingRail} on the agent.</li>
 *   <li>Calls {@code agent.invoke(input, null)} — runs the full ReAct loop.</li>
 *   <li>Reads the return value map to extract verify/degrade outcome.</li>
 *   <li>Reads the runtime trace from TraceCollectingRail.</li>
 *   <li>Runs {@link TraceAnalyzer} to produce an adjustment suggestion.</li>
 *   <li>Applies the suggestion: updates the mutable config for the next iteration.
 *       If the suggestion is NO_CHANGE, terminates early — converged.</li>
 * </ol>
 *
 * <p><b>SDK workaround details</b>:
 * <ul>
 *   <li>Trace data is collected via {@link AgentRail} hooks (no jar change).</li>
 *   <li>Verify outcome is read from the agent's return value {@code Map} — which is
 *       the standard ReActAgent contract (no jar change).</li>
 *   <li>Criteria adjustment uses unregisterRail + registerRail to swap the
 *       {@link CriteriaReplanBridgeRail} instance (BaseAgent API, no jar change).</li>
 *   <li>Verifier swap uses {@link MutableCriteriaVerifier} — a delegation wrapper
 *       that allows runtime delegate swap without touching the rail's final field
 *       (no jar change, no reflection).</li>
 *   <li>Prompt adjustment uses {@code SystemPromptBuilder.addSection} (jar API).</li>
 * </ul>
 *
 * <p><b>Honest boundary</b>:
 * <ul>
 *   <li>Each climb iteration creates a fresh agent — this is intentional: the
 *       decisionHistory inside CriteriaReplanBridgeRail is per-invocation, not
 *       accumulated across climbs. Hill climbing compares independent runs.</li>
 *   <li>The tuner does NOT modify an agent mid-invoke. All adjustments happen
 *       BETWEEN invocations.</li>
 *   <li>{@code TraceAnalyzer} is rule-based (zero LLM cost). An LLM-based
 *       trace analyzer is deferred (would require spawning a sub-agent, which
 *       adds complexity beyond Phase 1).</li>
 * </ul>
 */
public class HillClimbingTuner {

    /** Default max climb iterations before giving up. */
    public static final int DEFAULT_MAX_CLIMB = 3;

    private final int maxClimbIterations;
    private final TraceAnalyzer analyzer = new TraceAnalyzer();

    // -- Mutable config that evolves across climb iterations --

    private List<String> currentCriteria;
    private MutableCriteriaVerifier mutableVerifier;
    private int currentMaxReplan;
    // TODO(Phase2): temperature injection via ToolCallingEnforcingModel environment
    // variable override (LLM_THINKING pattern) — needs per-invoke config mgmt
    @SuppressWarnings("unused")
    private double currentTemperature;
    private String currentPromptSectionText;
    private boolean currentPromptSectionRegistered;

    // -- Tracking best result --

    private TraceFeedbackRecord bestTrace;
    private Object bestResult;
    private int bestIteration;

    /**
     * @param baseCriteria initial success criteria (same format as
     *                     {@code reactrails.criteria} property)
     * @param maxClimbIterations number of hill-climb iterations (≥ 1)
     */
    public HillClimbingTuner(List<String> baseCriteria, int maxClimbIterations) {
        this.currentCriteria = new ArrayList<>(baseCriteria);
        this.currentMaxReplan = 2;
        this.currentTemperature = 0.3;
        this.currentPromptSectionText = "";
        this.currentPromptSectionRegistered = false;
        this.maxClimbIterations = Math.max(1, maxClimbIterations);
    }

    /**
     * Convenience constructor with default max climb (3).
     */
    public HillClimbingTuner(List<String> baseCriteria) {
        this(baseCriteria, DEFAULT_MAX_CLIMB);
    }

    // ================================================================
    // Main entry point
    // ================================================================

    /**
     * Run the hill-climbing loop for the given input.
     *
     * @param input the agent input prompt (passed as-is to agent.invoke)
     * @return the best result found across all iterations, or null if all failed
     */
    public HillClimbingResult run(String input) {
        bestTrace = null;
        bestResult = null;
        bestIteration = -1;

        for (int i = 0; i < maxClimbIterations; i++) {
            // Build agent from current config
            ReActAgent agent = buildClimbAgent("climb-" + i);

            // Register trace collector
            TraceCollectingRail traceRail = new TraceCollectingRail();
            traceRail.setPriority(Integer.MAX_VALUE); // lowest priority: run after verify rails
            agent.registerRail(traceRail);

            // Run invocation
            traceRail.beginTrace();
            Object result;
            try {
                result = agent.invoke(input, null);
            } catch (Exception e) {
                // Failed invocation — no trace data, skip this iteration
                System.err.println("[hill-climb] iteration " + i + " invoke failed: " + e.getMessage());
                continue;
            }
            TraceFeedbackRecord trace = traceRail.endTrace();

            // Enrich trace from agent's return value
            enrichFromResult(trace, result);

            // Track best result (prefer PASS over FAIL, then lower model-call count)
            if (isBetter(trace, bestTrace)) {
                bestTrace = trace;
                bestResult = result;
                bestIteration = i;
            }

            // Analyze and adjust (or terminate if converged)
            TraceAdjustmentSuggestion suggestion = analyzer.analyze(trace);
            if (suggestion.action() == TraceAdjustmentSuggestion.Action.NO_CHANGE) {
                break; // converged — current config is adequate
            }
            applySuggestion(suggestion);

            // Log adjustment
            System.out.println("[hill-climb] iteration " + i
                    + " suggestion: " + suggestion.action()
                    + " reason: " + suggestion.reason());
        }

        return new HillClimbingResult(bestResult, bestTrace, bestIteration,
                new ArrayList<>(currentCriteria), currentMaxReplan);
    }

    // ================================================================
    // Agent construction
    // ================================================================

    /**
     * Build a ReActAgent with the current hill-climbing config.
     *
     * <p>Replicates the standard {@code ReactRailsAutoConfiguration} setup but
     * with config that evolves across iterations:
     * <ul>
     *   <li>{@link CriteriaReplanBridgeRail} with {@link MutableCriteriaVerifier}</li>
     *   <li>{@link PevReplanRail} with current maxReplan</li>
     *   <li>{@link RootCauseRail}</li>
     *   <li>{@link ReplanTool}</li>
     *   <li>Extra prompt section (if any from IMPROVE_PROMPT suggestions)</li>
     * </ul>
     */
    private ReActAgent buildClimbAgent(String name) {
        ReActAgent agent = new ReActAgent(AgentCard.builder().name(name).build());

        // -- Verifier --
        // Start with RuleBasedCriteriaVerifier; mutable wrapper allows runtime swap
        CriteriaVerifier initialVerifier = new RuleBasedCriteriaVerifier();
        this.mutableVerifier = new MutableCriteriaVerifier(initialVerifier);

        // -- Rails --

        // CriteriaReplanBridgeRail with mutable verifier
        ReplanRail sharedCounter = new ReplanRail(currentMaxReplan);
        CriteriaReplanBridgeRail criteriaRail = new CriteriaReplanBridgeRail(
                mutableVerifier, currentCriteria, sharedCounter);
        agent.registerRail(criteriaRail);

        // PevReplanRail (replaces old ReplanRail)
        PevReplanRail pevRail = new PevReplanRail(currentMaxReplan);
        agent.registerRail(pevRail);

        // ReplanTool (ability, not rail)
        ReplanTool.registerOnto(agent);

        // RootCauseRail
        agent.registerRail(new RootCauseRail());

        // -- Prompt section (from IMPROVE_PROMPT adjustments) --
        if (currentPromptSectionRegistered && !currentPromptSectionText.isEmpty()) {
            agent.getSystemPromptBuilder().addSection(
                    new PromptSection("hill-climb-guidance",
                            Map.of("zh", currentPromptSectionText,
                                   "en", currentPromptSectionText),
                            50));
        }

        return agent;
    }

    // ================================================================
    // Post-invoke result enrichment
    // ================================================================

    /**
     * Enrich the trace record from the agent's return value.
     *
     * <p>When a rail calls {@code ctx.requestForceFinish(map)}, the map becomes
     * the agent's return value. Keys match the constants in
     * {@link CriteriaReplanBridgeRail} and {@link PevReplanRail}.
     */
    @SuppressWarnings("unchecked")
    private static void enrichFromResult(TraceFeedbackRecord trace, Object result) {
        if (trace == null || result == null) {
            return;
        }
        if (!(result instanceof Map<?, ?> map)) {
            return;
        }

        trace.setForceFinished(true);

        // Criteria result
        Object criteriaResult = map.get(CriteriaReplanBridgeRail.RESULT_KEY);
        trace.setCriteriaResult(criteriaResult instanceof String s ? s : "");

        // Verified flag
        Object verified = map.get(CriteriaReplanBridgeRail.VERIFIED_KEY);
        trace.setVerified(verified instanceof Boolean b && b);

        // Degraded flag
        Object degraded = map.get(CriteriaReplanBridgeRail.DEGRADED_KEY);
        trace.setDegraded(degraded instanceof Boolean b && b);

        // Unmet criteria
        Object unmet = map.get(CriteriaReplanBridgeRail.UNMET_KEY);
        if (unmet instanceof List<?> list) {
            trace.setUnmetCriteria(list.stream()
                    .map(Object::toString)
                    .toList());
        }

        // Output (may be from either bridge rail or pev replan rail)
        Object output = map.get(CriteriaReplanBridgeRail.OUTPUT_KEY);
        if (output instanceof String s) {
            trace.setFinalOutput(s);
        }
    }

    // ================================================================
    // Best-result comparison
    // ================================================================

    /**
     * Compares two trace records to determine which is "better".
     * PASS > FAIL > null. Within same pass/fail, lower model-call count wins.
     */
    private static boolean isBetter(TraceFeedbackRecord candidate, TraceFeedbackRecord current) {
        if (candidate == null) return false;
        if (current == null) return true;

        // PASS beats FAIL
        if (candidate.isPass() != current.isPass()) {
            return candidate.isPass();
        }

        // Same outcome — fewer model calls is better (more efficient)
        return candidate.getModelCallCount() < current.getModelCallCount();
    }

    // ================================================================
    // Suggestion application
    // ================================================================

    /**
     * Apply a single adjustment suggestion to the mutable configuration.
     * The next call to {@link #buildClimbAgent} will incorporate the change.
     */
    private void applySuggestion(TraceAdjustmentSuggestion suggestion) {
        switch (suggestion.action()) {
            case RELAX_CRITERIA -> {
                // Remove the failing criteria and re-register with reduced set
                currentCriteria.removeAll(suggestion.targetCriteria());
            }
            case IMPROVE_PROMPT -> {
                currentPromptSectionText = suggestion.promptText();
                currentPromptSectionRegistered = true;
            }
            case INCREASE_REPLAN -> {
                currentMaxReplan = Math.min(currentMaxReplan + 1, 5);
            }
            case DECREASE_REPLAN -> {
                currentMaxReplan = Math.max(currentMaxReplan - 1, 1);
            }
            case UPGRADE_VERIFIER -> {
                // Swap mutable wrapper to a more sensitive verifier
                mutableVerifier.update(new GradientVerifier());
            }
            case DOWNGRADE_VERIFIER -> {
                // Swap back to rule-based
                mutableVerifier.update(new RuleBasedCriteriaVerifier());
            }
            case ADJUST_THRESHOLD -> {
                // For GradientVerifier: recreate with adjusted threshold
                mutableVerifier.update(new GradientVerifier(
                        suggestion.newThreshold(),
                        GradientVerifier.DEFAULT_CRITERION_PASS_THRESHOLD,
                        GradientVerifier.DEFAULT_COVERAGE_FLOOR));
            }
            case NO_CHANGE -> {
                // No-op — loop will terminate
            }
        }
    }

    // ================================================================
    // Result value class
    // ================================================================

    /**
     * The outcome of a hill-climbing run: the best trace + result pair found,
     * along with the configuration that produced it.
     */
    public record HillClimbingResult(
            Object result,
            TraceFeedbackRecord trace,
            int iteration,
            List<String> finalCriteria,
            int finalMaxReplan) {

        public boolean hasResult() {
            return result != null;
        }

        public boolean isVerified() {
            return trace != null && trace.isPass();
        }
    }
}
