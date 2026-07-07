/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.trace;


/**
 * Rule-based trace analyzer for the E-hill-climbing-trace-feedback species.
 * Zero LLM cost — all heuristics are deterministic, working on structural
 * patterns in the trace record.
 *
 * <p>Patterns detected (in priority order, first match wins):
 * <ol>
 *   <li><b>Tool-device failure cascade</b>: repeated exceptions on the same tool
 *       → {@link TraceAdjustmentSuggestion.Action#INCREASE_REPLAN} (skip the broken
 *       tool via replan) or {@code IMPROVE_PROMPT} (warn agent about the tool).</li>
 *   <li><b>Criteria mismatch</b>: ticket is verified FAIL with specific unmet criteria
 *       → {@code RELAX_CRITERIA} for the failing criteria (too strict or ambiguous).</li>
 *   <li><b>Over-struggle</b>: degraded + high model-call count (≥ 3)
 *       → {@code IMPROVE_PROMPT} with initial guidance about approach and structure.</li>
 *   <li><b>Replan-exhausted</b>: FAIL + modelCalls &gt; 2 × expected (agent ran out of
 *       replan budget) → {@code INCREASE_REPLAN}.</li>
 *   <li><b>Premature terminate</b>: output too short (&lt; 50 chars) + verified
 *       → {@code IMPROVE_PROMPT} demanding substance.</li>
 *   <li><b>Convergence</b>: PASS with low model-call count (≤ 2)
 *       → {@code NO_CHANGE} — current config is effective.</li>
 * </ol>
 *
 * <p><b>Empirical calibration</b> (from react-rails e2e runs on deepseek-v4-flash / Qwen3.5-35B):
 * <ul>
 *   <li>#5 GDP/CPI/通胀率: verify FAIL → criteria too strict (required 3 numeric references
 *       while LLM gave 2) → ideal adjust: RELAX to 2/3 match or GradientVerifier.</li>
 *   <li>#2 zero-replan first-principles: PASS with 1 modelCall → NO_CHANGE.</li>
 *   <li>#1/6 over-replan: 2 replans + degrade → INCREASE_REPLAN (3 would save it) or
 *       IMPROVE_PROMPT (de-emphasize replan).</li>
 * </ul>
 */
public class TraceAnalyzer {

    /** Model call count above which the agent is considered "struggling". */
    public static final int STRUGGLE_CALL_THRESHOLD = 3;

    /** Output length below which the agent is considered "too brief". */
    public static final int SHORT_OUTPUT_THRESHOLD = 50;

    /**
     * Analyze a single trace record and produce an adjustment suggestion.
     *
     * <p>First-match-wins: only the highest-priority matching rule fires.
     * This prevents cascading adjustments from a single trace observation.
     *
     * @param trace the completed trace from one agent.invoke call
     * @return a suggestion; {@link TraceAdjustmentSuggestion#NO_CHANGE} if
     *         no actionable pattern is found
     */
    public TraceAdjustmentSuggestion analyze(TraceFeedbackRecord trace) {
        if (trace == null || !trace.hasSignal()) {
            return TraceAdjustmentSuggestion.NO_CHANGE;
        }

        // Priority 1: Repeated tool exceptions — device failure cascade
        if (!trace.getToolExceptions().isEmpty()) {
            // At least one tool failed — increase replan budget to let agent work around it
            return TraceAdjustmentSuggestion.increaseReplan(
                    "工具异常(" + String.join(",", trace.getToolExceptions()) + "): "
                    + "增加 replan 预算让 agent 绕过故障工具");
        }

        // Priority 2: Criteria FAIL with specific unmet criteria — criteria too strict
        if (trace.isFail() && !trace.getUnmetCriteria().isEmpty()) {
            return TraceAdjustmentSuggestion.relaxCriteria(
                    trace.getUnmetCriteria(),
                    "标准未满足: " + String.join(", ", trace.getUnmetCriteria())
                    + " — 建议放宽或拆分子标准");
        }

        // Priority 3: Degraded + high model call count — agent struggled despite effort
        if (trace.isStruggled()) {
            return TraceAdjustmentSuggestion.improvePrompt(
                    "请务必给出详细分析，包含具体数据和推理链。从多个角度展开，"
                    + "确保回答完整覆盖所有成功标准。如遇到困难，首先检查是否遗漏了关键信息。",
                    "Agent 经过 " + trace.getModelCallCount() + " 次模型调用后降级 "
                    + "— 添加初始引导提示提升首次回答质量");
        }

        // Priority 4: FAIL with high model calls — replan budget exhausted
        if (trace.isFail() && trace.getModelCallCount() > STRUGGLE_CALL_THRESHOLD * 2) {
            return TraceAdjustmentSuggestion.increaseReplan(
                    "replan 预算耗尽 (" + trace.getModelCallCount() + " calls) "
                    + "— 增加 maxReplan");
        }

        // Priority 5: PASS but very short output — add substance requirement
        if (trace.isPass() && trace.getFinalOutput().length() < SHORT_OUTPUT_THRESHOLD) {
            return TraceAdjustmentSuggestion.improvePrompt(
                    "输出过于简短。请提供详细分析，包含具体数据、推理过程和结论。",
                    "输出仅 " + trace.getFinalOutput().length() + " 字符 — 要求详细输出");
        }

        // Priority 6: Clean PASS with low effort — config is good, no change
        if (trace.isPass() && trace.getModelCallCount() <= 2) {
            return TraceAdjustmentSuggestion.NO_CHANGE;
        }

        // Fallback: no actionable pattern
        return TraceAdjustmentSuggestion.NO_CHANGE;
    }
}
