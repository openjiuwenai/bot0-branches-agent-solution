/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.kernel;

import java.util.Set;

/**
 * What to do next — 3-state sealed dispatch, output of {@link PevKernel#toReplanAction},
 * consumed by the PEV main loop's explicit dispatcher.
 *
 * <p>Layers (keep distinct — do not collapse into one type):
 * <ul>
 *   <li>{@link RootCause} — <b>why</b> verify failed (diagnosis).</li>
 *   <li><b>ReplanAction</b> — <b>what</b> to do (dispatch), carrying the data the
 *       action needs (which nodes, what feedback).</li>
 * </ul>
 *
 * <p>The sealed hierarchy constrains the supported action variants; the Java 17 dispatcher
 * handles each permitted variant explicitly and rejects unsupported runtime values.
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
