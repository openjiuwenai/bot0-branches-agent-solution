/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.trace;

import java.util.List;

/**
 * A concrete suggestion produced by {@link TraceAnalyzer} after examining a
 * {@link TraceFeedbackRecord}. The {@link HillClimbingTuner} interprets each
 * suggestion and applies the corresponding change to the agent configuration
 * before the next climb iteration.
 *
 * <p>Each suggestion has a single {@link Action} and optional payload fields:
 * <ul>
 *   <li>{@code targetCriteria} — which criteria to relax/remove</li>
 *   <li>{@code promptText} — new system-prompt section text to inject</li>
 *   <li>{@code newThreshold} — numeric threshold override (for verifier tuning)</li>
 *   <li>{@code reason} — human-readable explanation for logging/debugging</li>
 * </ul>
 *
 * <p>A special {@link #NO_CHANGE} sentinel indicates the current configuration
 * is adequate — the hill-climbing loop should terminate.
 */
public record TraceAdjustmentSuggestion(
        Action action,
        List<String> targetCriteria,
        String promptText,
        double newThreshold,
        String reason) {

    /** The kind of adjustment to apply. */
    public enum Action {
        /** Remove or relax the listed criteria (too strict). */
        RELAX_CRITERIA,
        /** Inject an additional section into the system prompt. */
        IMPROVE_PROMPT,
        /** Increase maxReplan budget (agent needs more iterations). */
        INCREASE_REPLAN,
        /** Decrease maxReplan budget (agent wastes replan rounds). */
        DECREASE_REPLAN,
        /** Switch to a more sensitive verifier (e.g., GradientVerifier). */
        UPGRADE_VERIFIER,
        /** Switch to a more lenient verifier (e.g., RuleBasedCriteriaVerifier). */
        DOWNGRADE_VERIFIER,
        /** Adjust a numeric threshold on the current verifier. */
        ADJUST_THRESHOLD,
        /** No change needed — configuration is adequate. */
        NO_CHANGE
    }

    /** Sentinel: keep current configuration. */
    public static final TraceAdjustmentSuggestion NO_CHANGE =
            new TraceAdjustmentSuggestion(Action.NO_CHANGE, List.of(), "", 0.0,
                    "当前配置已足够，无需调整");

    // ================================================================
    // Factory methods
    // ================================================================

    public static TraceAdjustmentSuggestion relaxCriteria(List<String> criteria, String reason) {
        return new TraceAdjustmentSuggestion(
                Action.RELAX_CRITERIA, List.copyOf(criteria), "", 0.0, reason);
    }

    public static TraceAdjustmentSuggestion improvePrompt(String text, String reason) {
        return new TraceAdjustmentSuggestion(
                Action.IMPROVE_PROMPT, List.of(), text, 0.0, reason);
    }

    public static TraceAdjustmentSuggestion increaseReplan(String reason) {
        return new TraceAdjustmentSuggestion(
                Action.INCREASE_REPLAN, List.of(), "", 0.0, reason);
    }

    public static TraceAdjustmentSuggestion decreaseReplan(String reason) {
        return new TraceAdjustmentSuggestion(
                Action.DECREASE_REPLAN, List.of(), "", 0.0, reason);
    }

    public static TraceAdjustmentSuggestion upgradeVerifier(String reason) {
        return new TraceAdjustmentSuggestion(
                Action.UPGRADE_VERIFIER, List.of(), "", 0.0, reason);
    }

    public static TraceAdjustmentSuggestion downgradeVerifier(String reason) {
        return new TraceAdjustmentSuggestion(
                Action.DOWNGRADE_VERIFIER, List.of(), "", 0.0, reason);
    }

    public static TraceAdjustmentSuggestion adjustThreshold(double threshold, String reason) {
        return new TraceAdjustmentSuggestion(
                Action.ADJUST_THRESHOLD, List.of(), "", threshold, reason);
    }
}
