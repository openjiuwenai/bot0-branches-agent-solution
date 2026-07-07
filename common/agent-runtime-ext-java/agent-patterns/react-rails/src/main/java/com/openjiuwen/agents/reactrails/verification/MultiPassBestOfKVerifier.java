/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.types.Violation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * K=2 multi-dimensional best-of-K verifier.
 *
 * <p>Scores output along two independent axes:
 * <ol>
 *   <li><b>严谨性 (Rigor)</b> — quantitative density, reasoning markers, tool evidence</li>
 *   <li><b>完整性 (Completeness)</b> — criterion keyword surface coverage</li>
 * </ol>
 *
 * <p>Threshold gate — PASS: both {@code ≥0.7}; partial+steering: both {@code ≥0.5}, any {@code <0.7};
 * dimension FAIL: any {@code <0.5}; total FAIL: both {@code <0.5} (replan signal).
 *
 * <p>Deterministic (zero LLM cost). Dimension-specific violation reasons carry
 * the weak-dimension identity into {@code buildCorrectionHint}, enabling
 * targeted steering instead of blanket retry.
 *
 * <p><b>Empirical anchors</b>:
 * <ul>
 *   <li>#2 First-principles self-check prompt → 0 replan: the system prompt frames replan
 *       as "quality failure", driving first-pass excellence and reducing unnecessary replan.</li>
 *   <li>#4 pushSteering → retry → corrected: the mechanism is validated — multi-dimensional
 *       feedback pinpoints the weak dimension for precise retry.</li>
 *   <li>#1/6 ReplanTool description "放弃"→"调整策略" + imperative hint → over-replan:
 *       violation reasons avoid imperative replan framing, only signalling replan when
 *       <b>both</b> dimensions FAIL.</li>
 * </ul>
 *
 * @see CriteriaReplanBridgeRail
 * @see RuleBasedCriteriaVerifier
 */
public class MultiPassBestOfKVerifier implements CriteriaVerifier {

    /** Both dimensions must reach this threshold to pass verification. */
    public static final double PASS_THRESHOLD = 0.7;

    /** Below this threshold on any dimension = dimension FAIL. */
    public static final double FAIL_THRESHOLD = 0.5;

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern REASONING_MARKERS = Pattern.compile(
            "因此|因为|所以|由于|推导|计算|原因|影响|对比|趋势|conclude|therefore|because|hence",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "在", "是", "和", "就", "不", "都", "一", "一个", "上", "也",
            "the", "a", "an", "is", "are", "was", "to", "of", "in", "for", "on",
            "with", "at", "by", "from", "as", "into", "and", "or", "not", "be",
            "this", "that", "it", "its", "must", "should", "will");

    @Override
    public List<Violation> verify(List<String> successCriteria,
                                   String output,
                                   String decisionHistory) {
        List<Violation> violations = new ArrayList<>();
        String safeOutput = output == null ? "" : output;
        String safeHistory = decisionHistory == null ? "" : decisionHistory;

        for (String criterion : successCriteria) {
            if (criterion == null || criterion.isBlank()) {
                continue;
            }

            double rigor = scoreRigor(safeOutput, safeHistory);
            double complete = scoreCompleteness(criterion, safeOutput, safeHistory);

            if (rigor < FAIL_THRESHOLD && complete < FAIL_THRESHOLD) {
                // Total FAIL: both dimensions below fail threshold → replan signal
                violations.add(new Violation(criterion, String.format(
                        "K=2双FAIL(严谨性=%.2f/完整性=%.2f): 整体路径需replan",
                        rigor, complete)));
            } else if (rigor < FAIL_THRESHOLD) {
                // Dimension 1 FAIL only
                violations.add(new Violation(criterion, String.format(
                        "K=2严谨性FAIL(严谨性=%.2f): 缺少定量分析/推理链,请补充数据或推导",
                        rigor)));
            } else if (complete < FAIL_THRESHOLD) {
                // Dimension 2 FAIL only
                violations.add(new Violation(criterion, String.format(
                        "K=2完整性FAIL(完整性=%.2f): 未覆盖该标准关键内容",
                        complete)));
            } else if (rigor < PASS_THRESHOLD) {
                // Dimension 1 below pass but above fail → partial + steering
                violations.add(new Violation(criterion, String.format(
                        "K=2部分通过(严谨性=%.2f): 建议补充定量或因果证据", rigor)));
            } else if (complete < PASS_THRESHOLD) {
                // Dimension 2 below pass but above fail → partial + steering
                violations.add(new Violation(criterion, String.format(
                        "K=2部分通过(完整性=%.2f): 建议明确回答此维度", complete)));
            }
            // else: both ≥ PASS_THRESHOLD → no violation for this criterion
        }
        return violations;
    }

    /**
     * Score dimension 1 (Rigor): analytical depth via numeric density + causal markers + tool evidence.
     *
     * <p>Components (all deterministic, regex-based):
     * <ul>
     *   <li>Numeric density (0.0–0.4): each number token adds 0.08, capped at 0.4</li>
     *   <li>Reasoning markers (0.0–0.3): each causal/inferential word adds 0.075, capped at 0.3</li>
     *   <li>Tool evidence (0.0–0.15): if history contains {@code __probe_tool__} or {@code __replan__}</li>
     *   <li>Substantial output (0.0–0.15): if output length &gt; 200 characters</li>
     * </ul>
     *
     * <p>Empirical: #5 ToolCallingEnforcingModel probe — Qwen/deepseek both call __probe_tool__
     * on first probe. Tool evidence captures grounded reasoning in the decision history.
     */
    private static double scoreRigor(String output, String history) {
        double score = 0.0;

        // Numeric density (0.0–0.4): each number is evidence of quantitative thinking
        long numCount = NUMERIC_PATTERN.matcher(output).results().count();
        score += Math.min(numCount * 0.08, 0.4);

        // Reasoning markers (0.0–0.3): causal/inferential language
        long markerCount = REASONING_MARKERS.matcher(output).results().count();
        score += Math.min(markerCount * 0.075, 0.3);

        // Tool evidence (0.0–0.15): tool calls indicate grounded reasoning
        if (history.contains("__probe_tool__") || history.contains("__replan__")) {
            score += 0.15;
        }

        // Substantial output suggests depth (0.0–0.15)
        if (output.length() > 200) {
            score += 0.15;
        }

        return Math.min(score, 1.0);
    }

    /**
     * Score dimension 2 (Completeness): criterion keyword surface coverage.
     *
     * <p>Extracts meaningful keywords from the criterion (length ≥ 2, non-stop-words),
     * then checks what fraction appear in the output or decision history.
     *
     * <p>Returns 1.0 when the criterion has no extractable keywords (common words only),
     * avoiding false fail on generic criteria.
     */
    private static double scoreCompleteness(String criterion, String output, String history) {
        String outputLower = output.toLowerCase(Locale.ROOT);
        String historyLower = history.toLowerCase(Locale.ROOT);

        String[] words = criterion.toLowerCase(Locale.ROOT).split("[\\s,，、。.]+");
        List<String> keywords = new ArrayList<>();
        for (String w : words) {
            if (w.length() >= 2 && !STOP_WORDS.contains(w)) {
                keywords.add(w);
            }
        }
        if (keywords.isEmpty()) {
            return 1.0;
        }

        long matched = keywords.stream()
                .filter(kw -> outputLower.contains(kw) || historyLower.contains(kw))
                .count();
        return (double) matched / keywords.size();
    }
}
