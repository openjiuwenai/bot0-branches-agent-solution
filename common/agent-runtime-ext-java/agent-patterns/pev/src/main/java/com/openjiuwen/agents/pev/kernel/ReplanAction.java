/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.kernel;

import java.util.Set;

/**
 * What to do next — 3-state sealed dispatch, output of {@link PevKernel#toReplanAction},
 * consumed by the PEV main loop's switch.
 *
 * <p>Layers (keep distinct — do not collapse into one type):
 * <ul>
 *   <li>{@link RootCause} — <b>why</b> verify failed (diagnosis).</li>
 *   <li><b>ReplanAction</b> — <b>what</b> to do (dispatch), carrying the data the
 *       action needs (which nodes, what feedback).</li>
 * </ul>
 *
 * <p>Sealed permits compile-time exhaustiveness: any switch over {@code ReplanAction}
 * that drops a case arm fails to compile.
 *
 * @since 2026-07
 */
public sealed interface ReplanAction
        permits ReplanAction.LocalReplan, ReplanAction.GlobalReplan, ReplanAction.AcceptPartial {

    /**
     * Re-execute the failed nodes only, injecting corrective feedback.
     */
    record LocalReplan(Set<String> failedNodes, String feedback) implements ReplanAction {
        public LocalReplan {
            failedNodes = Set.copyOf(failedNodes);
        }
    }

    /**
     * Discard the current plan and re-plan the whole goal.
     */
    record GlobalReplan(String feedback) implements ReplanAction {
    }

    /**
     * Stop the loop and return a partial / degraded result honestly.
     *
     * @since 2026-07
     */
    record AcceptPartial(String reason) implements ReplanAction {
    }
}
