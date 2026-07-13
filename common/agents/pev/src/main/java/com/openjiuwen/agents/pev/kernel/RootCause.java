/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.kernel;

import java.util.Set;

/**
 * Why a verify step failed — 3-state sealed diagnosis, output of {@link PevKernel#diagnoseRootCause}.
 *
 * <p>The three states are prioritised by signal certainty (not LLM self-report):
 * <ul>
 *   <li>{@link DeviceFailure} — a tool / infrastructure node broke. Replanning cannot
 *       heal a broken device; retrying the same input re-raises the same error.</li>
 *   <li>{@link PerceptionUnreliable} — the verifier itself is untrustworthy (threw /
 *       returned non-parseable output). Its FAILED verdict cannot be trusted.</li>
 *   <li>{@link PlanOrAnswerError} — device and perception are sound, but the plan or
 *       answer content is wrong. Replanning can fix this.</li>
 * </ul>
 *
 * <p>The sealed hierarchy constrains the supported diagnosis variants; the Java 17
 * dispatcher handles each permitted variant explicitly and rejects unsupported values.
 *
 * @since 2026-07
 */
public sealed interface RootCause
        permits RootCause.DeviceFailure, RootCause.PlanOrAnswerError, RootCause.PerceptionUnreliable {
    /**
     * Tool / infrastructure failed on these nodes.
     */
    record DeviceFailure(Set<String> nodes) implements RootCause {
        public DeviceFailure {
            nodes = Set.copyOf(nodes);
        }
    }

    /**
     * Plan or answer content is wrong; device and perception are sound.
     */
    record PlanOrAnswerError(Set<String> nodes) implements RootCause {
        public PlanOrAnswerError {
            nodes = Set.copyOf(nodes);
        }
    }

    /**
     * Verifier itself is untrustworthy.
     *
     * @since 2026-07
     */
    record PerceptionUnreliable(boolean isVerifierThrown) implements RootCause {
    }
}
