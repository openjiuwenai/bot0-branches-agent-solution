/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.kernel;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * PEV decision core — pure functions turning verify/execution signals into a dispatch action.
 *
 * <p>Zero LLM, zero framework coupling: the same logic can be unit-tested exhaustively and
 * reused by sibling agent patterns built on the same base (a future EDPA can depend on this
 * kernel for its diagnose/dispatch).
 *
 * <p>Two functions:
 * <ul>
 *   <li>{@link #diagnoseRootCause} — signals → {@link RootCause} (3-state diagnosis).</li>
 *   <li>{@link #toReplanAction} — {@link RootCause} → {@link ReplanAction} (3-state dispatch,
 *       sealed switch, compiler-enforced exhaustive).</li>
 * </ul>
 *
 * @since 2026-07
 */
public final class PevKernel {

    private PevKernel() {
    }

    /**
     * Verifier's structured verdict.
     *
     * <p>Two distinct PerceptionUnreliable signals (kept separate so the diagnose function
     * can tell <b>why</b> the verifier is untrustworthy):
     * <ul>
     *   <li>{@code threw} — the verifier raised a {@link RuntimeException} (verify threw).
     *       Set by {@link com.openjiuwen.agents.pev.agent.PEVAgent}'s catch block.</li>
     *   <li>{@code parseFailure} — the verifier returned non-JSON / blank output
     *       (returned, but unparseable).</li>
     * </ul>
     * Either signal triggers {@link RootCause.PerceptionUnreliable}; {@code threw} wins
     * over {@code parseFailure} when both happen to be true (a throw means no return value).
     *
     * @since 2026-07
     */
    public record VerifyResult(boolean passed, Set<String> failedNodes, String feedback, boolean parseFailure,
            boolean threw) {
        public VerifyResult {
            failedNodes = Set.copyOf(failedNodes);
        }
        public VerifyResult(boolean passed, Set<String> failedNodes, String feedback) {
            this(passed, failedNodes, feedback, false, false);
        }
        public VerifyResult(boolean passed, Set<String> failedNodes, String feedback, boolean parseFailure) {
            this(passed, failedNodes, feedback, parseFailure, false);
        }
    }

    /**
     * Diagnose why verify failed, from deterministic signals — not LLM self-report.
     *
     * <p>Priority: perception-unreliable > device-failure > plan/answer-error.
     * Cross-validates the failed-node set against the per-node {@link NodeResult} map,
     * so a lost side-channel still yields {@link RootCause.DeviceFailure} when the node
     * map carries structural DeviceFailure evidence.
     *
     * @param verify verifier verdict carrying failed nodes and reliability flags
     * @param execFailedNodes nodes that failed during execution
     * @param nodeResults execution results keyed by node id
     * @return root cause derived from verifier and execution signals
     */
    public static RootCause diagnoseRootCause(VerifyResult verify, Set<String> execFailedNodes,
            Map<String, NodeResult> nodeResults) {
        // threw dominates parseFailure: a throw means no return value to parse at all.
        if (verify.threw()) {
            return new RootCause.PerceptionUnreliable(true);
        }
        if (verify.parseFailure()) {
            return new RootCause.PerceptionUnreliable(false);
        }
        Set<String> device = new LinkedHashSet<>();
        for (var entry : nodeResults.entrySet()) {
            if (entry.getValue() instanceof NodeResult.DeviceFailure) {
                device.add(entry.getKey());
            }
        }
        if (execFailedNodes != null) {
            device.addAll(execFailedNodes);
        }
        Set<String> hit = new HashSet<>(device);
        hit.retainAll(verify.failedNodes());
        if (!hit.isEmpty()) {
            return new RootCause.DeviceFailure(hit);
        }
        return new RootCause.PlanOrAnswerError(verify.failedNodes());
    }

    /**
     * Map a root cause to a dispatch action — sealed switch, compiler-enforced exhaustive.
     *
     * <p>IFF contract (deliberate, tested):
     * <ul>
     *   <li>{@link RootCause.DeviceFailure} / {@link RootCause.PerceptionUnreliable} →
     *       {@link ReplanAction.AcceptPartial} — <b>never retry</b>. A broken device does
     *       not heal on retry; an untrustworthy verifier's FAILED verdict cannot be acted on.</li>
     *   <li>{@link RootCause.PlanOrAnswerError} → {@link ReplanAction.LocalReplan} (≤2 failed
     *       nodes) / {@link ReplanAction.GlobalReplan} (&gt;2 or empty) — <b>never AcceptPartial</b>.
     *       Content errors are recoverable by replan; degrading early gives up recoverable work.</li>
     * </ul>
     *
     * @param cause diagnosed reason for the failed verification
     * @param feedback verifier or replanning feedback to carry into the action
     * @param failedNodes nodes reported as failed by the verifier
     * @return dispatch action selected for the diagnosed root cause
     */
    public static ReplanAction toReplanAction(RootCause cause, String feedback, Set<String> failedNodes) {
        return switch (cause) {
            case RootCause.DeviceFailure d -> new ReplanAction.AcceptPartial(
                    "Device failure: " + d.nodes() + " — replan cannot fix broken tools/infra");
            case RootCause.PerceptionUnreliable p -> new ReplanAction.AcceptPartial("Perception unreliable: verifier "
                    + (p.verifierThrew() ? "threw" : "returned null") + " — cannot trust its FAILED verdict");
            case RootCause.PlanOrAnswerError pe -> {
                Set<String> nodes = failedNodes == null ? Set.of() : failedNodes;
                if (nodes.isEmpty()) {
                    yield new ReplanAction.GlobalReplan(feedback);
                }
                if (nodes.size() <= 2) {
                    yield new ReplanAction.LocalReplan(nodes, feedback);
                }
                yield new ReplanAction.GlobalReplan(feedback);
            }
        };
    }
}
