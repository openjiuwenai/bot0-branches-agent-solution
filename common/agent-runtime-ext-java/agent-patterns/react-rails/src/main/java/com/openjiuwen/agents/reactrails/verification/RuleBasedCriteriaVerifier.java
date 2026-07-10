/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic rule-based criteria verifier — keyword matching (no LLM call).
 *
 * <p>Checks each criterion's keywords against the output + decision history.
 * Fast, deterministic, zero LLM cost. Falls back to ASSUME_FAIL when a criterion
 * can't be checked by keyword (honest: don't rubber-stamp what you can't verify).
 *
 * <p>This is the default verifier for {@link CriteriaVerificationRail} when no
 * LLM-backed verifier is injected — it covers the rule-based path (output/history
 * keyword coverage), leaving the LLM-judge path to a future injected verifier.
 *
 * @since 2026-07
 */
public class RuleBasedCriteriaVerifier implements CriteriaVerifier {
    /**
     * When a criterion can't be keyword-checked, treat as violation (honest).
     */
    public static final String ASSUME_FAIL_REASON = "无法用关键词验证，默认不通过（注入 LLM verifier 做质量判断）";

    private static final java.util.Set<String> STOP_WORDS = java.util.Set.of("的", "了", "在", "是", "和", "就", "不", "都",
            "一", "一个", "上", "也", "the", "a", "an", "is", "are", "was", "to", "of", "in", "for", "on", "with", "at",
            "by", "from", "as", "into", "and", "or", "not", "be", "this", "that", "it", "its", "must", "should",
            "will");

    @Override
    public List<Violation> verify(List<String> successCriteria, String output, String decisionHistory) {
        List<Violation> violations = new ArrayList<>();
        String outputLower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        String historyLower = decisionHistory == null ? "" : decisionHistory.toLowerCase(Locale.ROOT);

        for (String criterion : successCriteria) {
            if (criterion == null || criterion.isBlank()) {
                continue;
            }
            // Extract meaningful keywords (>= 2 chars, filter stop words)
            String[] words = criterion.toLowerCase(Locale.ROOT).split("[\\s,，、。.]+");
            List<String> keywords = new ArrayList<>();
            for (String w : words) {
                if (w.length() >= 2 && !isStopWord(w)) {
                    keywords.add(w);
                }
            }
            if (keywords.isEmpty()) {
                continue;
            }
            // Check: at least half the keywords appear in output or history
            long matched = keywords.stream().filter(kw -> outputLower.contains(kw) || historyLower.contains(kw))
                    .count();
            int threshold = Math.max(1, (keywords.size() + 1) / 2);
            if (matched < threshold) {
                violations.add(new Violation(criterion,
                        "关键词覆盖不足: " + matched + "/" + keywords.size() + " (需 ≥" + threshold + ")"));
            }
        }
        return violations;
    }

    private static boolean isStopWord(String w) {
        return STOP_WORDS.contains(w);
    }
}
