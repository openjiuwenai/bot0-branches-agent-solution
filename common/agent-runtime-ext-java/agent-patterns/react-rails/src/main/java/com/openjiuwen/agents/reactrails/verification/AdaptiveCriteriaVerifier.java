/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Adaptive criteria verifier with three-level severity — NEAR_PASS / WIDE_MISS / COMPLETE_MISS.
 *
 * <p>Drop-in compatible with the {@link CriteriaVerifier} interface. Severity is encoded
 * in {@link Violation#reason()} as a deterministic marker prefix {@code "SEV:<level>|"}
 * so the existing {@link Violation} record stays unchanged.
 *
 * <p>Use {@link #severityOf(Violation)} to decode severity from a violation, and
 * {@link #aggregate(List)} to get the worst severity across all violations.
 *
 * <p><b>Thresholds</b> (per criterion, keyword-based):
 * <ul>
 *   <li>{@code matched == total} → {@link Severity#PASS}</li>
 *   <li>{@code matched >= ceil(total/2) && matched < total} → {@link Severity#NEAR_PASS}</li>
 *   <li>{@code matched >= 1 && matched < ceil(total/2)} → {@link Severity#WIDE_MISS}</li>
 *   <li>{@code matched == 0} → {@link Severity#COMPLETE_MISS}</li>
 * </ul>
 *
 * <p><b>Aggregate</b> (worst wins): COMPLETE_MISS &gt; WIDE_MISS &gt; NEAR_PASS &gt; PASS.
 *
 * <p>Empirical motivators:
 * <ul>
 *   <li>#4 pushSteering → retry → corrected: binary PASS/FAIL misses the gradation between
 *       "almost there" (NEAR_PASS → light steering) and "way off" (WIDE_MISS → strong steering).</li>
 *   <li>#1 imperative replan prompt triggered 2 replans → degrade: COMPLETE_MISS immediate
 *       degrade saves wasted retry rounds for zero-match output.</li>
 * </ul>
 */
public class AdaptiveCriteriaVerifier implements CriteriaVerifier {

    /** Severity levels ordered from best to worst. */
    public enum Severity {
        PASS(0),          // all keywords matched
        NEAR_PASS(1),     // matched >= threshold but < total
        WIDE_MISS(2),     // matched >= 1 but < threshold
        COMPLETE_MISS(3); // matched == 0

        private final int weight;

        Severity(int weight) {
            this.weight = weight;
        }

        public int weight() {
            return weight;
        }

        /** True if this severity is worse than {@code other}. */
        public boolean worseThan(Severity other) {
            return this.weight > other.weight;
        }
    }

    private static final String SEV_PREFIX = "SEV:";
    private static final String SEV_SEPARATOR = "|";

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "和", "就", "不", "都", "一", "一个", "上", "也",
            "the", "a", "an", "is", "are", "was", "to", "of", "in", "for", "on",
            "with", "at", "by", "from", "as", "into", "and", "or", "not", "be",
            "this", "that", "it", "its", "must", "should", "will");

    @Override
    public List<Violation> verify(List<String> successCriteria, String output, String decisionHistory) {
        List<Violation> violations = new ArrayList<>();
        String outputLower = (output == null ? "" : output).toLowerCase(Locale.ROOT);
        String historyLower = (decisionHistory == null ? "" : decisionHistory).toLowerCase(Locale.ROOT);

        for (String criterion : successCriteria) {
            if (criterion == null || criterion.isBlank()) {
                continue;
            }

            List<String> keywords = extractKeywords(criterion);
            if (keywords.isEmpty()) {
                continue;
            }

            long matched = keywords.stream()
                    .filter(kw -> outputLower.contains(kw) || historyLower.contains(kw))
                    .count();

            Severity sev = classifySeverity((int) matched, keywords.size());
            if (sev != Severity.PASS) {
                violations.add(new Violation(criterion, encodeSeverity(sev, (int) matched, keywords.size())));
            }
        }
        return violations;
    }

    /**
     * Extract meaningful keywords from a criterion string (same logic as
     * {@link RuleBasedCriteriaVerifier} for compatibility).
     */
    static List<String> extractKeywords(String criterion) {
        String[] words = criterion.toLowerCase(Locale.ROOT).split("[\\s,，、。.]+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= 2 && !STOP_WORDS.contains(w)) {
                keywords.add(w);
            }
        }
        return keywords;
    }

    /**
     * Classify severity per criterion based on keyword match ratio.
     * Package-private for testability.
     */
    static Severity classifySeverity(int matched, int total) {
        if (matched == total) {
            return Severity.PASS;
        }
        if (matched == 0) {
            return Severity.COMPLETE_MISS;
        }
        int threshold = (total + 1) / 2; // ceil(total / 2)
        if (matched >= threshold) {
            return Severity.NEAR_PASS;
        }
        return Severity.WIDE_MISS;
    }

    /**
     * Encode severity into Violation.reason with a deterministic marker prefix.
     * Format: {@code "SEV:<level>|matched <m>/<t>, missing <n>"}
     */
    private static String encodeSeverity(Severity sev, int matched, int total) {
        return SEV_PREFIX + sev.name() + SEV_SEPARATOR
                + "关键词覆盖: " + matched + "/" + total
                + ", 缺失: " + (total - matched);
    }

    /**
     * Decode severity from a Violation's reason marker.
     * Returns {@link Severity#PASS} for unmarked or legacy violations (graceful degradation).
     */
    public static Severity severityOf(Violation v) {
        String r = v.reason();
        if (r == null || !r.startsWith(SEV_PREFIX)) {
            return Severity.PASS;
        }
        int end = r.indexOf(SEV_SEPARATOR, SEV_PREFIX.length());
        if (end < 0) {
            return Severity.PASS;
        }
        try {
            return Severity.valueOf(r.substring(SEV_PREFIX.length(), end));
        } catch (IllegalArgumentException e) {
            return Severity.PASS;
        }
    }

    /**
     * Aggregate multiple violations to the worst overall severity.
     * Empty or null input returns PASS.
     */
    public static Severity aggregate(List<Violation> violations) {
        if (violations == null || violations.isEmpty()) {
            return Severity.PASS;
        }
        Severity worst = Severity.PASS;
        for (Violation v : violations) {
            Severity s = severityOf(v);
            if (s.worseThan(worst)) {
                worst = s;
            }
        }
        return worst;
    }
}
