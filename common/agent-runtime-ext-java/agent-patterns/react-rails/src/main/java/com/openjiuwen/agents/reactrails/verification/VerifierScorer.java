/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import java.util.List;
import java.util.Map;

/**
 * A single verifier scoring function in a voting ensemble — independently evaluates all
 * success criteria against the agent's output and returns per-criterion scores with feedback.
 *
 * <p>Implementations must be stateless or thread-safe: {@link VotingCriteriaVerifier}
 * invokes scorers from virtual threads (Java 21+ virtual threads per scorer).
 *
 * <p>Three built-in implementations are provided:
 * <ul>
 *   <li>{@link VotingCriteriaVerifier.KeywordScorer} — deterministic keyword match ratio</li>
 *   <li>{@link VotingCriteriaVerifier.RigorScorer} — output quality heuristics</li>
 *   <li>{@link VotingCriteriaVerifier.LlmJudgeScorer} — external LLM-as-judge (requires {@code Model})</li>
 * </ul>
 *
 * <p>Zero coupling: each scorer has no knowledge of other scorers or the voting mechanism.
 * This enables safe parallel execution and independent unit testing.
 *
 * @see VotingCriteriaVerifier
 */
public interface VerifierScorer {

    /**
     * Evaluate all success criteria against the agent's output.
     *
     * @param successCriteria the criteria to evaluate
     * @param output          the agent's final output text
     * @param decisionHistory tool-call history (concatenated, for history-coverage checks)
     * @return a verdict with per-criterion scores in [0.0, 1.0] and overall feedback
     */
    ScoredVerdict score(List<String> successCriteria, String output, String decisionHistory);

    /**
     * Human-readable label for this scorer (used in violation feedback).
     * Default implementation returns the simple class name.
     */
    default String label() {
        return getClass().getSimpleName();
    }

    /**
     * Result from a single scorer: per-criterion scores + overall feedback string.
     *
     * @param criterionScores map of criterion → score in [0.0, 1.0] (1.0 = fully met)
     * @param feedback        human-readable explanation of this scorer's evaluation
     */
    record ScoredVerdict(Map<String, Double> criterionScores, String feedback) {

        /** Build a verdict with empty feedback. */
        public ScoredVerdict(Map<String, Double> criterionScores) {
            this(criterionScores, "");
        }

        /** Empty verdict — all criteria scored 0.0 with the given feedback. */
        public static ScoredVerdict allZero(List<String> criteria, String feedback) {
            Map<String, Double> map = new java.util.HashMap<>();
            for (String c : criteria) {
                if (c != null && !c.isBlank()) map.put(c, 0.0);
            }
            return new ScoredVerdict(map, feedback);
        }
    }
}
