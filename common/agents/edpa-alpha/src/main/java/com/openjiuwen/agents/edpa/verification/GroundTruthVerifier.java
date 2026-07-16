/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.verification;

import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Ground-truth verifier — deterministic-first (GEPA 确定性兜底铁律: 涉数值/逻辑/合规的 V
 * 绝不 LLM-as-judge).
 *
 * <p><b>Layered verification</b>: injected {@link DeterministicChecker}s (zero-LLM 算子:
 * 金额/阈值/规则计算) own the criteria they match → keyword-based
 * {@link RuleBasedCriteriaVerifier} fallback for the rest. The deterministic layer never
 * guesses; it computes. This is the 一等公民 — EdpaAutoConfiguration defaults to it
 * (replacing bare RuleBasedCriteriaVerifier), so injected deterministic checkers take
 * priority over keyword coverage.
 *
 * <p>Layer order: deterministic (compute) &gt; keyword (coverage proxy) &gt; LLM-judge
 * (probabilistic, future). Each criterion is verified by exactly one layer — the first
 * that matches.
 *
 * <p><b>诚实边界（4-lens 校正）</b>：EdpaAutoConfiguration 默认注入 {@code new GroundTruthVerifier()}
 * （空 checkers）→ 生产 100% 走 keyword fallback。确定性优先层需注入 DeterministicChecker
 * 实现（如 ClaimDeterministicChecker）才生效；当前全仓零生产 DeterministicChecker 实现
 * （SPI 占位，等待业务接线）。
 *
 * @since 2026-07
 */
public class GroundTruthVerifier implements CriteriaVerifier {
    private final List<DeterministicChecker> checkers;
    private final RuleBasedCriteriaVerifier keywordFallback;

    /**
     * Construct with deterministic checkers (priority over keyword fallback).
     *
     * @param checkers deterministic checkers that own specific criteria
     */
    public GroundTruthVerifier(List<DeterministicChecker> checkers) {
        this.checkers = List.copyOf(java.util.Objects.requireNonNull(checkers, "checkers"));
        this.keywordFallback = new RuleBasedCriteriaVerifier();
    }

    /**
     * No deterministic checkers — pure keyword fallback (equivalent to RuleBasedCriteriaVerifier).
     */
    public GroundTruthVerifier() {
        this(List.of());
    }

    /**
     * Verify the given success criteria against the produced output using the
     * layered deterministic-first strategy: each criterion is matched against
     * injected {@link DeterministicChecker}s first, and only the unmatched
     * remainder falls through to the keyword-based fallback.
     *
     * @param successCriteria  the success criteria to verify; {@code null} or
     *                         blank entries are skipped
     * @param output           the candidate output text to check against
     * @param decisionHistory  the agent decision history used as extra context
     * @return                 an unmodifiable-style list of {@link Violation}s
     *                         found; empty when every criterion passes
     * @throws NullPointerException if {@code successCriteria} is {@code null}
     */
    @Override
    public List<Violation> verify(List<String> successCriteria, String output, String decisionHistory) {
        List<String> keywordCriteria = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        for (String criterion : successCriteria) {
            if (criterion == null || criterion.isBlank()) {
                continue;
            }
            DeterministicChecker matched = null;
            for (DeterministicChecker c : checkers) {
                if (c.matches(criterion)) {
                    matched = c;
                    break;
                }
            }
            if (matched != null) {
                Violation v = matched.check(criterion, output, decisionHistory);
                if (v != null) {
                    violations.add(v);
                }
            } else {
                keywordCriteria.add(criterion);
            }
        }
        // keyword fallback for criteria no deterministic checker owned
        if (!keywordCriteria.isEmpty()) {
            violations.addAll(keywordFallback.verify(keywordCriteria, output, decisionHistory));
        }
        return violations;
    }
}
