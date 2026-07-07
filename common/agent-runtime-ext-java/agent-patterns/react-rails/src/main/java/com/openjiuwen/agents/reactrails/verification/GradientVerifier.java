/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Gradient verifier: splits each success criterion into semantic dimensions,
 * scores each dimension independently, and returns gradient metadata (covered vs
 * missing dimensions) for precision steering.
 *
 * <p>Contrast {@link RuleBasedCriteriaVerifier} (binary pass/fail per criterion) and
 * {@link AdaptiveCriteriaVerifier} (three-level severity encoded in reason string).
 * This verifier provides dimension-level granularity absent in both predecessors.
 *
 * <p><b>Thresholds</b>:
 * <ul>
 *   <li>{@code dimensionThreshold} (default 1.0): all keywords in one dimension must match</li>
 *   <li>{@code criterionPassThreshold} (default 1.0): all dimensions must be covered to PASS</li>
 *   <li>{@code coverageFloor} (default 0.25): below this, use "covered under" hint instead of partial</li>
 * </ul>
 *
 * <p><b>Empirical anchors</b>:
 * <ul>
 *   <li>#4 GDP/CPI/通胀率 verify FAIL → gradient steering pinpoints missing dimensions → retry passes</li>
 * </ul>
 */
public class GradientVerifier implements CriteriaVerifier {

    public static final double DEFAULT_DIMENSION_THRESHOLD = 1.0;
    public static final double DEFAULT_CRITERION_PASS_THRESHOLD = 1.0;
    public static final double DEFAULT_COVERAGE_FLOOR = 0.25;

    private final double dimensionThreshold;
    private final double criterionPassThreshold;
    private final double coverageFloor;

    public GradientVerifier() {
        this(DEFAULT_DIMENSION_THRESHOLD, DEFAULT_CRITERION_PASS_THRESHOLD, DEFAULT_COVERAGE_FLOOR);
    }

    /**
     * @param dimensionThreshold     fraction of keywords that must match in a dimension (default 1.0)
     * @param criterionPassThreshold fraction of dimensions that must be covered to PASS (default 1.0)
     * @param coverageFloor          below this ratio, hint lists all dimensions instead of partial (default 0.25)
     */
    public GradientVerifier(double dimensionThreshold, double criterionPassThreshold,
                            double coverageFloor) {
        this.dimensionThreshold = dimensionThreshold;
        this.criterionPassThreshold = criterionPassThreshold;
        this.coverageFloor = coverageFloor;
    }

    /** A semantic dimension = raw segment label + extracted keyword set. */
    public record Dimension(String label, List<String> keywords) {}

    @Override
    public List<Violation> verify(List<String> successCriteria, String output,
                                  String decisionHistory) {
        List<Violation> violations = new ArrayList<>();
        String outputLower = output == null ? "" : output.toLowerCase(Locale.ROOT);
        String historyLower = decisionHistory == null ? "" : decisionHistory.toLowerCase(Locale.ROOT);
        String combined = outputLower + " | " + historyLower;

        for (String criterion : successCriteria) {
            if (criterion == null || criterion.isBlank()) {
                continue;
            }

            List<Dimension> dims = extractDimensions(criterion);
            if (dims.isEmpty()) {
                continue;
            }

            List<String> coveredLabels = new ArrayList<>();
            List<String> missingLabels = new ArrayList<>();
            int coveredCount = 0;

            for (Dimension dim : dims) {
                long matchCount = dim.keywords().stream()
                        .filter(kw -> combined.contains(kw))
                        .count();
                boolean dimCovered = dim.keywords().isEmpty()
                        || (double) matchCount / dim.keywords().size() >= dimensionThreshold;
                if (dimCovered) {
                    coveredCount++;
                    coveredLabels.add(dim.label());
                } else {
                    missingLabels.add(dim.label());
                }
            }

            double coverage = dims.isEmpty() ? 1.0 : (double) coveredCount / dims.size();
            if (coverage >= criterionPassThreshold) {
                continue;
            }

            boolean isPartial = coverage >= coverageFloor;
            String reason;
            Map<String, Object> meta = new LinkedHashMap<>();

            if (isPartial) {
                reason = String.format("部分覆盖(%d/%d). 已覆盖: %s. 请补充: %s",
                        coveredCount, dims.size(),
                        String.join("、", coveredLabels),
                        String.join("、", missingLabels));
            } else {
                reason = String.format("覆盖不足(%d/%d). 需要覆盖的维度: %s",
                        coveredCount, dims.size(),
                        dims.stream().map(Dimension::label)
                                .collect(Collectors.joining("、")));
            }

            meta.put("coveredCount", coveredCount);
            meta.put("totalCount", dims.size());
            meta.put("coverage", coverage);
            meta.put("isPartial", isPartial);
            meta.put("covered", List.copyOf(coveredLabels));
            meta.put("missing", List.copyOf(missingLabels));
            if (!isPartial) {
                meta.put("allDimensions", dims.stream().map(Dimension::label).toList());
            }

            violations.add(new Violation(criterion, reason, Map.copyOf(meta)));
        }
        return violations;
    }

    /**
     * Split criterion by Chinese/English punctuation into semantic dimensions,
     * then extract meaningful keywords per dimension (>= 2 chars, non-stop-word).
     *
     * <p>Each segment is tokenized at CJK/Latin boundaries so that mixed text
     * like "包含GDP" produces separate tokens ["包含", "gdp"] rather than a single
     * opaque token "包含gdp". Stop words are filtered per-token.
     *
     * <p>Fallback: no punctuation → whole criterion as single dimension.
     */
    private List<Dimension> extractDimensions(String criterion) {
        String lower = criterion.toLowerCase(Locale.ROOT);
        String[] segments = lower.split("[，,、。.;；：]+");
        List<Dimension> dims = new ArrayList<>();

        for (String seg : segments) {
            seg = seg.trim();
            if (seg.length() < 2) {
                continue;
            }
            List<String> kws = extractKeywordsFromSegment(seg);
            if (!kws.isEmpty()) {
                dims.add(new Dimension(seg, kws));
            }
        }

        // Fallback: no punctuation-based segmentation → treat whole criterion as one dimension
        if (dims.isEmpty()) {
            List<String> kws = extractKeywordsFromSegment(lower);
            if (!kws.isEmpty()) {
                dims.add(new Dimension(criterion, kws));
            }
        }

        return dims;
    }

    /**
     * Tokenize a segment into runs of the same character class (Latin vs CJK),
     * then filter stop words from CJK runs using a sliding 2-char window.
     *
     * <p>CJK stop words are detected as substrings within CJK tokens. After removal,
     * remaining characters are re-grouped into potential keywords (min 2 chars).
     * Latin letter runs are kept directly (min 2 chars).
     *
     * <p>Examples:
     * <ul>
     *   <li>"包含gdp" → "包含" removed (stop word), "gdp" kept → ["gdp"]</li>
     *   <li>"cpi和通胀率分析" → "cpi" kept, "和" removed, "通胀率" kept, "分析" removed → ["cpi", "通胀率"]</li>
     *   <li>"建议" → ["建议"]</li>
     * </ul>
     */
    private static List<String> extractKeywordsFromSegment(String seg) {
        List<String> keywords = new ArrayList<>();
        StringBuilder latinBuf = new StringBuilder();
        StringBuilder cjkBuf = new StringBuilder();

        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            boolean isLatinChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean isCjkChar = isCjk(c);

            if (isLatinChar || (c >= '0' && c <= '9')) {
                // Flush pending CJK buffer
                if (cjkBuf.length() > 0) {
                    flushCjkKeywords(cjkBuf, keywords);
                    cjkBuf.setLength(0);
                }
                latinBuf.append(c);
            } else if (isCjkChar) {
                // Flush pending Latin buffer
                if (latinBuf.length() > 0) {
                    if (latinBuf.length() >= 2) {
                        keywords.add(latinBuf.toString());
                    }
                    latinBuf.setLength(0);
                }
                cjkBuf.append(c);
            } else {
                // Non-letter/non-CJK (punctuation, space) — flush both buffers
                if (latinBuf.length() > 0) {
                    if (latinBuf.length() >= 2) {
                        keywords.add(latinBuf.toString());
                    }
                    latinBuf.setLength(0);
                }
                if (cjkBuf.length() > 0) {
                    flushCjkKeywords(cjkBuf, keywords);
                    cjkBuf.setLength(0);
                }
            }
        }
        // Flush remaining buffers
        if (latinBuf.length() >= 2) {
            keywords.add(latinBuf.toString());
        }
        if (cjkBuf.length() > 0) {
            flushCjkKeywords(cjkBuf, keywords);
        }

        return keywords;
    }

    /**
     * Process a CJK run: slide a 2-char window to detect multi-character stop words,
     * also check single characters. Re-group remaining non-stop characters into keywords.
     */
    private static void flushCjkKeywords(StringBuilder cjkBuf, List<String> keywords) {
        String cjk = cjkBuf.toString();
        StringBuilder cleaned = new StringBuilder();
        int i = 0;
        while (i < cjk.length()) {
            // Try 2-char stop word first (longer match wins)
            if (i + 2 <= cjk.length() && STOP_WORDS.contains(cjk.substring(i, i + 2))) {
                i += 2;
                continue;
            }
            // Try 1-char stop word
            String single = cjk.substring(i, i + 1);
            if (STOP_WORDS.contains(single)) {
                i += 1;
                continue;
            }
            cleaned.append(cjk.charAt(i));
            i += 1;
        }
        if (cleaned.length() >= 2) {
            keywords.add(cleaned.toString());
        }
    }

    /** True if the character is in a CJK block (ideograph). */
    private static boolean isCjk(char c) {
        Character.UnicodeBlock b = Character.UnicodeBlock.of(c);
        return b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || b == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A;
    }

    /** Extended stop-word set (adds criterion-verb filters to the common set). */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "和", "就", "不", "都", "一", "一个", "上", "也",
            "the", "a", "an", "is", "are", "was", "to", "of", "in", "for", "on",
            "with", "at", "by", "from", "as", "into", "and", "or", "not", "be",
            "this", "that", "it", "its", "must", "should", "will",
            "需要", "包含", "分析", "给出", "进行", "提供");
}
