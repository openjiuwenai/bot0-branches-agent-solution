/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.kernel;

import java.util.Set;

/**
 * EDPA decision core — pure function turning root-cause into dispatch action.
 *
 * <p>Zero LLM, zero framework coupling. Replaces the pev kernel dependency:
 * EDPA-alpha and PEV are peer agents that must not depend on each other.
 *
 * <p><b>Drift boundary (honest)</b>: {@link #toReplanAction} is a byte-identical port of
 * {@code PevKernel.toReplanAction}. There is currently NO automated cross-module sync
 * (no property/equivalence test). If PEV changes the IFF contract (thresholds, new RootCause
 * variant), this function must be manually updated. A cross-module property test is tracked
 * as follow-up (xuefanfan Q2).
 *
 * @since 2026-07
 */
public final class EdpaKernel {
    private EdpaKernel() {
    }

    /**
     * Map a root cause to a dispatch action (sealed types, pure function, zero LLM).
     *
     * @param cause diagnosed reason for the failed verification
     * @param feedback verifier or replanning feedback
     * @param failedNodes nodes reported as failed
     * @return dispatch action
     */
    public static ReplanAction toReplanAction(RootCause cause, String feedback, Set<String> failedNodes) {
        if (cause instanceof RootCause.DeviceFailure deviceFailure) {
            return new ReplanAction.AcceptPartial(
                    "Device failure: " + deviceFailure.nodes() + " — replan cannot fix broken tools/infra");
        }
        if (cause instanceof RootCause.PerceptionUnreliable perceptionUnreliable) {
            return new ReplanAction.AcceptPartial("Perception unreliable: verifier "
                    + (perceptionUnreliable.isVerifierThrown() ? "threw" : "returned null")
                    + " — cannot trust its FAILED verdict");
        }
        if (cause instanceof RootCause.PlanOrAnswerError) {
            Set<String> nodes = failedNodes == null ? Set.of() : failedNodes;
            if (nodes.isEmpty()) {
                return new ReplanAction.GlobalReplan(feedback);
            }
            if (nodes.size() <= 2) {
                return new ReplanAction.LocalReplan(nodes, feedback);
            }
            return new ReplanAction.GlobalReplan(feedback);
        }
        throw new IllegalArgumentException("Unsupported root cause: " + cause);
    }
}
