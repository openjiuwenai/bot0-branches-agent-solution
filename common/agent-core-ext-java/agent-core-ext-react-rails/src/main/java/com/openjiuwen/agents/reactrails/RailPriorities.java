/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails;

/**
 * Single source of rail priority values (process-wide coordination table).
 *
 * <p>agent-core sorts callbacks in DESCENDING priority order (higher fires first,
 * registration order irrelevant — bytecode + RailPriorityOrderTest verified). Rails
 * MUST reference this table instead of restating numbers in their own Javadoc —
 * restated copies drift (4-lens finding: one rail's comment claimed a neighbour's
 * priority as 0 when the real value is the default 50, and two comments cited a
 * {@code VotingCriticVerifierRail(100)} that does not exist anywhere).
 *
 * <p><b>react-rails table</b> (descending execution order within a shared hook):
 * <table border="1">
 *   <caption>react-rails rail priorities</caption>
 *   <tr><th>Rail</th><th>Priority</th><th>Hook(s)</th><th>Note</th></tr>
 *   <tr><td>{@code ObservingRail}</td><td>{@code Integer.MIN_VALUE}</td><td>all peeked</td>
 *       <td>last in every fan-out — must observe all other rails' effects</td></tr>
 *   <tr><td>{@code PreCompletionChecklistRail}</td><td>{@link #PRE_COMPLETION_CHECKLIST} (80)</td>
 *       <td>beforeModelCall + afterModelCall</td><td></td></tr>
 *   <tr><td>{@code StagnationDetectionRail}</td><td>{@link #STAGNATION_DETECTION} (50)</td>
 *       <td>afterModelCall + onToolException</td><td>same tier as the default-50 rails below</td></tr>
 *   <tr><td>{@code CriteriaVerificationRail}</td><td>default 50 (not set)</td><td>afterModelCall</td>
 *       <td>ties resolve by registration order (stable sort)</td></tr>
 *   <tr><td>{@code CriteriaReplanBridgeRail}</td><td>default 50 (not set)</td><td>afterModelCall</td>
 *       <td>ties resolve by registration order</td></tr>
 *   <tr><td>{@code ReplanRail}</td><td>default 50 (not set)</td><td>afterModelCall</td>
 *       <td>shares ONE instance with the bridge rail (budget contract)</td></tr>
 *   <tr><td>{@code HistoryCompressorRail}</td><td>default 50 (not set)</td><td>afterModelCall</td>
 *       <td>ties resolve by registration order</td></tr>
 *   <tr><td>{@code RootCauseRail}</td><td>default 50 (not set)</td>
 *       <td>onToolException + afterModelCall</td><td></td></tr>
 * </table>
 *
 * <p><b>EDPA-alpha rails on the same scale</b> (cross-module, for coordination):
 * UserInputCaptureRail 95 → ExploreRail 90 → (PreCompletion 80 above) →
 * ProactiveConvergenceRail 70 → default-50 tier → SteeringProvisionRail 1.
 *
 * <p><b>Honest boundary — same-tier rails</b>: rails that share a priority value (the
 * default-50 tier) have NO guaranteed relative order — {@code List.sort} stability makes
 * it registration order, which is an implementation detail, not a contract. Do not
 * document a before/after relationship between same-tier rails.
 *
 * @since 2026-08
 */
public final class RailPriorities {
    /**
     * PreCompletionChecklistRail (80) — after model call metrics + before-model guardrails;
     * below UserInputCaptureRail(95)/ExploreRail(90) on the beforeModelCall hook in an
     * EDPA-assembled agent.
     */
    public static final int PRE_COMPLETION_CHECKLIST = 80;

    /**
     * StagnationDetectionRail (50) — same tier as the default-50 rails; relative order
     * within the tier is registration order (not a contract).
     */
    public static final int STAGNATION_DETECTION = 50;

    private RailPriorities() {
    }
}
