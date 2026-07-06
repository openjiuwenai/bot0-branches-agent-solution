/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

import java.util.List;

/**
 * External-judge criteria verifier — checks whether the agent's final output meets the
 * success criteria. This is the capability that gitcode's ReActAgent lacks (it has no
 * built-in verify; the SDK's {@code VerificationRail} is prompt-injection, not external-judge).
 *
 * <p>Two verification strategies:
 * <ul>
 *   <li><b>Rule-based</b> (deterministic): keyword/pattern matching, no LLM call.</li>
 *   <li><b>LLM-as-judge</b> (probabilistic): a separate LLM call evaluates quality/coverage.</li>
 * </ul>
 */
public interface CriteriaVerifier {

    /**
     * Verify all success criteria against the agent's output + decision history.
     *
     * @param successCriteria the criteria to check (from the goal)
     * @param output          the agent's final output text
     * @param decisionHistory the tool-call history (concatenated, for history-coverage checks)
     * @return violations for unmet criteria; empty list = all pass
     */
    List<Violation> verify(List<String> successCriteria, String output, String decisionHistory);
}