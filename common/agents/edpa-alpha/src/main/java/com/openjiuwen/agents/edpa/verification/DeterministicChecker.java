/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

/**
 * Deterministic checker SPI — ground-truth verification for a specific criterion
 * (金额/阈值/规则计算, zero LLM). {@link GroundTruthVerifier} dispatches to matching
 * checkers before falling back to keyword-based verification.
 *
 * <p><b>GEPA 确定性兜底铁律</b>: 涉数值/逻辑/合规的 V 绝不 LLM-as-judge — this SPI is the
 * deterministic layer (compute, don't guess). Example checkers: claim deductible
 * (85% 共担比例), insurance threshold (医疗≥50000/重疾≥100000/意外≥30000).
 *
 * <p>A checker declares which criteria it owns ({@link #matches}) and deterministically
 * verifies them ({@link #check}). It must be side-effect free and pure — same inputs,
 * same verdict, no LLM, no randomness.
 *
 * @since 2026-07
 */
public interface DeterministicChecker {
    /**
     * Does this checker apply to the given criterion (by keyword/pattern)?
     *
     * @param criterion the success criterion text
     * @return true if this checker can deterministically verify this criterion
     */
    boolean matches(String criterion);

    /**
     * Deterministically verify the criterion against output + decision history.
     *
     * @param criterion       the success criterion to check
     * @param output          the agent's final output text
     * @param decisionHistory the tool-call history (concatenated, for context)
     * @return null if the criterion passes, or a {@link Violation} (with metadata:
     *         covered/missing dimensions) if it fails
     */
    Violation check(String criterion, String output, String decisionHistory);
}
