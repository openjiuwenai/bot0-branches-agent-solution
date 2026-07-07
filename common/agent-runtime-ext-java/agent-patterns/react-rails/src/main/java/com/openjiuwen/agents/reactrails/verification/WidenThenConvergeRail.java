/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * F-widen-then-converge rail: 先扩后收模式。
 *
 * <p><b>发散阶段</b>：首轮 final answer 走 {@link AngleScorer} 角度评分选择器。
 * 不判对错，而是从 3+ 角度中选最高分角度，{@code pushSteering} 通知 LLM 收敛。
 * 无高分角度时触发 {@code __replan__} 换角度重发散（最多 {@link #MAX_DIVERGENT_ROUNDS} 次）。
 *
 * <p><b>收敛阶段</b>：复用 CriteriaVerifier verify-correct-retry 三出口 gate。
 * pushSteering 做修正（修正通过后 forceFinish(verified=true)），
 * retry 耗尽后 forceFinish(degraded=true)。
 *
 * <p>与 {@link CriteriaReplanBridgeRail} 的关系：F 模式是 bridge rail 中 reactive-retry
 * 模式的上层封装——前者在 retry 前加了主动发散阶段。收敛阶段逻辑 1:1 复用。
 *
 * <p><b>阈值常量</b>（可通过构造函数覆盖）：
 * <ul>
 *   <li>{@link #DEFAULT_STRONG_THRESHOLD} = 3.5：角度评分 >= 此值 → 选该角度直接进入收敛</li>
 *   <li>{@link #DEFAULT_WEAK_THRESHOLD} = 2.0：角度评分 >= 此值但 &lt; STRONG → 收敛但带风险提示</li>
 *   <li>{@link #MAX_DIVERGENT_ROUNDS} = 2：发散阶段最大轮次</li>
 * </ul>
 *
 * <p><b>系统 prompt 注入设计</b>（精确措辞，用于 LLM 初始化 prompt）：
 * <pre>{@code
 * 你的认知过程遵循「先扩后收」的第一性原理：优秀的问题解决者总是先拉开搜索空间，
 * 再从里面选最好的方向深入。不充分发散的收敛是盲目的。
 *
 * 第一轮指令（发散）：
 * - 从至少 3 个不同角度分析问题
 * - 每个角度给出具体分析，包括推理、数据和案例（不要只列标题）
 * - 用编号 1. 2. 3. 明确区分不同角度
 *
 * 后续指令（收敛）：
 * - 系统会评估你的各角度分析质量并指定最佳角度
 * - 请基于指定角度深入，确保满足所有成功标准
 * - 如果发现当前角度无法满足标准，可以调用 __replan__ 换其他初始角度
 *
 * 关于 __replan__（调整策略工具）：
 * - 它的用途是当你发现所有当前角度的分析都不够深入时，主动调整分析框架
 * - 调用 __replan__ 不是放弃——是主动拓宽搜索空间，让你能找到更优的切入角度
 * - 在输出完整分析后再决定是否需要换角度，不要过早调用
 *
 * 记住：发散是为了让收敛更好，收敛是为了让发散有结果。两阶段缺一不可。
 * }</pre>
 *
 * <p>上述 prompt 措辞基于 6 条实证数据设计（详见种 F 物种设计文档）。
 * 核心设计原则：
 * <ol>
 *   <li>__replan__ 放在条件从句（"如果发现...可以调用"），不在祈使句位置 → 避免 over-replan</li>
 *   <li>保留第一性原理框架（"先扩后收"）但追加条件式 __replan__ 权限 → 保留 zero-replan 框架优势</li>
 *   <li>__replan__ 正面 framing（"调整策略"、"主动拓宽搜索空间"）→ 健康 replan 频率</li>
 * </ol>
 */
public class WidenThenConvergeRail extends AgentRail {

    /** 角度评分 >= 此值 → 选该角度直接进入收敛（强信号，无风险提示）。 */
    public static final double DEFAULT_STRONG_THRESHOLD = 3.5;

    /** 角度评分 >= 此值但 < STRONG → 收敛但带风险提示。 */
    public static final double DEFAULT_WEAK_THRESHOLD = 2.0;

    /** 发散阶段最大轮次（超过此限制直接进入收敛兜底，不继续重试发散）。 */
    public static final int MAX_DIVERGENT_ROUNDS = 2;

    /** 结果键名（与 CriteriaReplanBridgeRail 对齐，便于外部消费者统一读取）。 */
    public static final String OUTPUT_KEY = "output";
    public static final String VERIFIED_KEY = "criteria_verified";
    public static final String RESULT_KEY = "criteria_result";
    public static final String DEGRADED_KEY = "degraded";
    public static final String UNMET_KEY = "unmet_criteria";
    public static final String RETRY_COUNT_KEY = "criteria_retry_count";

    /** 发散阶段附加结果键：最终选中的角度标签。 */
    public static final String SELECTED_ANGLE_KEY = "selected_angle";

    private final AngleScorer angleScorer;
    private final CriteriaVerifier fallbackVerifier;
    private final List<String> successCriteria;
    private final ReplanRail replanRail;
    private final double strongThreshold;
    private final double weakThreshold;

    private final List<String> decisionHistory = new ArrayList<>();
    private boolean divergentPhase = true;
    private int divergentRoundCount = 0;

    /**
     * @param angleScorer      角度评分器（发散阶段用）
     * @param fallbackVerifier 标准验证器（收敛阶段用）
     * @param successCriteria  成功标准列表
     * @param replanRail       共享 replan 计数器（收敛阶段 retry 预算）
     */
    public WidenThenConvergeRail(AngleScorer angleScorer,
                                  CriteriaVerifier fallbackVerifier,
                                  List<String> successCriteria,
                                  ReplanRail replanRail) {
        this(angleScorer, fallbackVerifier, successCriteria, replanRail,
                DEFAULT_STRONG_THRESHOLD, DEFAULT_WEAK_THRESHOLD);
    }

    /**
     * @param angleScorer      角度评分器（发散阶段用）
     * @param fallbackVerifier 标准验证器（收敛阶段用）
     * @param successCriteria  成功标准列表
     * @param replanRail       共享 replan 计数器
     * @param strongThreshold  阈值 A：强角度平均分下限（默认 3.5）
     * @param weakThreshold    阈值 B：弱角度平均分下限（默认 2.0），弱于此值触发重发散
     */
    public WidenThenConvergeRail(AngleScorer angleScorer,
                                  CriteriaVerifier fallbackVerifier,
                                  List<String> successCriteria,
                                  ReplanRail replanRail,
                                  double strongThreshold,
                                  double weakThreshold) {
        this.angleScorer = angleScorer;
        this.fallbackVerifier = fallbackVerifier;
        this.successCriteria = List.copyOf(successCriteria);
        this.replanRail = replanRail;
        this.strongThreshold = strongThreshold;
        this.weakThreshold = weakThreshold;
    }

    // ============================================================
    // 主入口：afterModelCall 钩子
    // ============================================================

    /**
     * 模型回调钩子：终态答案分发散/收敛两阶段处理，工具轮积累决策历史。
     *
     * <p>三出口已验证（IFF 范式）：
     * <ul>
     *   <li>verify pass → forceFinish(verified=true)</li>
     *   <li>verify fail + retries remaining → pushSteering 修正</li>
     *   <li>verify fail + retries exhausted → forceFinish(degraded=true)</li>
     * </ul>
     */
    @Override
    public synchronized void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }

        if (isFinalAnswer(msg)) {
            String output = contentOf(msg);
            if (divergentPhase) {
                handleDivergentPhase(ctx, output);
            } else {
                handleConvergentPhase(ctx, output);
            }
        } else {
            accumulateToolCalls(msg);
        }
    }

    // ============================================================
    // 发散阶段处理
    // ============================================================

    /**
     * 发散阶段：对 LLM 输出做角度评分 → 三出口决策树。
     *
     * <ol>
     *   <li><b>强角度 (>= strongThreshold)</b> → 选最佳角度，进入收敛</li>
     *   <li><b>边界角度 (>= weakThreshold)</b> → 选最佳角度进入收敛 + 风险提示</li>
     *   <li><b>弱角度 (&lt; weakThreshold)</b> → 要求 __replan__ 换角度重发散（最多 MAX_DIVERGENT_ROUNDS 次）</li>
     * </ol>
     */
    private void handleDivergentPhase(AgentCallbackContext ctx, String output) {
        divergentRoundCount++;
        List<AngleScorer.ScoredAngle> angles = angleScorer.extractAngles(output, successCriteria);

        if (angles.isEmpty()) {
            // 无角度可解析 → 要求重发（加明确格式指引）
            String steer;
            if (divergentRoundCount >= MAX_DIVERGENT_ROUNDS) {
                steer = "请直接输出您的最佳回答（无法解析多角度格式）。";
                divergentPhase = false;
            } else {
                steer = "未检测到多角度分析。请从至少 3 个不同角度分析问题，"
                        + "用 \"1. \" \"2. \" \"3. \" 编号区分。";
            }
            ctx.pushSteering(steer);
            return;
        }

        AngleScorer.ScoredAngle best = angles.get(0);
        double bestAvg = best.average();

        if (bestAvg >= strongThreshold) {
            // 出口 A: 有高质量角度 → 选它，进入收敛
            this.divergentPhase = false;
            ctx.pushSteering(buildConvergeSteering(best, bestAvg, false));

        } else if (bestAvg >= weakThreshold) {
            // 出口 B: 边界角度 → 选它收敛但带风险提示
            this.divergentPhase = false;
            ctx.pushSteering(buildConvergeSteering(best, bestAvg, true));

        } else {
            // 出口 C: 所有角度太弱 → 要求 __replan__ 换角度重发散
            if (divergentRoundCount >= MAX_DIVERGENT_ROUNDS) {
                // 已耗尽发散轮次 → 强行用最佳角度收敛
                this.divergentPhase = false;
                ctx.pushSteering("已尝试多次发散但角度质量不足。"
                        + "请基于当前最佳角度直接给出满足成功标准的答案。");
            } else {
                ctx.pushSteering(String.format(Locale.ROOT,
                    "当前各分析角度深度不足（最高评分 %.1f/5）。"
                    + "请调用 __replan__ 从全新角度重新发散，"
                    + "确保每个角度包含具体分析、数据或推理，而非表面提及。",
                    bestAvg));
                // 保持在发散阶段，下一轮 LLM replan 后重新角度评分
            }
        }
    }

    /** 构建收敛阶段的 steering 消息。 */
    private static String buildConvergeSteering(AngleScorer.ScoredAngle best, double avg, boolean borderline) {
        String base = String.format(Locale.ROOT,
                "您已覆盖多个分析角度。请基于角度「%s」深入分析，"
                + "确保满足所有成功标准。（角度评分 %.1f/5）",
                best.label(), avg);
        if (borderline) {
            base += " 此角度评分偏中低，如深入后发现无法满足成功标准，"
                    + "请调用 __replan__ 换其他角度。";
        }
        return base;
    }

    // ============================================================
    // 收敛阶段处理（复用 CriteriaReplanBridgeRail 逻辑）
    // ============================================================

    /**
     * 收敛阶段：标准验证 → 三出口 gate。
     *
     * <ol>
     *   <li>verify pass → forceFinish(verified=true)</li>
     *   <li>verify fail + retries remaining → pushSteering 修正，继续循环</li>
     *   <li>verify fail + retries exhausted → forceFinish(degraded=true)</li>
     * </ol>
     */
    private void handleConvergentPhase(AgentCallbackContext ctx, String output) {
        String historyStr = String.join(" | ", decisionHistory);
        List<Violation> violations = fallbackVerifier.verify(successCriteria, output, historyStr);

        if (violations.isEmpty()) {
            // Exit 1: 验证通过 → 锁定终态
            ctx.requestForceFinish(verifiedResult(output));
        } else {
            boolean overLimit = replanRail.incrementAndCheckOverLimit();
            if (overLimit) {
                // Exit 3: retry 耗尽 → forceFinish degraded
                ctx.requestForceFinish(degradedResult(output, violations));
            } else {
                // Exit 2: retry 剩余 → pushSteering 修正，继续循环
                ctx.pushSteering(buildCorrectionHint(violations));
            }
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    private static boolean isFinalAnswer(AssistantMessage msg) {
        return msg.getToolCalls() == null || msg.getToolCalls().isEmpty();
    }

    private void accumulateToolCalls(AssistantMessage msg) {
        if (msg.getToolCalls() == null) return;
        for (ToolCall tc : msg.getToolCalls()) {
            decisionHistory.add(tc.getName() + "(" + tc.getArguments() + ")");
        }
    }

    private static String contentOf(AssistantMessage msg) {
        String content = msg.getContentAsString();
        return content != null ? content : "";
    }

    private static String buildCorrectionHint(List<Violation> violations) {
        return "您的回答未能满足以下成功标准，请据此修改后重新回答：\n"
            + violations.stream()
                .map(v -> "- " + v.criterion() + ": " + v.reason())
                .collect(Collectors.joining("\n"))
            + "\n如需放弃当前路径请调用 __replan__ 工具。";
    }

    private static Map<String, Object> verifiedResult(String output) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(OUTPUT_KEY, output);
        result.put(VERIFIED_KEY, true);
        result.put(RESULT_KEY, "PASS");
        return result;
    }

    private Map<String, Object> degradedResult(String output, List<Violation> violations) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(OUTPUT_KEY, output);
        result.put(VERIFIED_KEY, false);
        result.put(RESULT_KEY, "FAIL");
        result.put(DEGRADED_KEY, true);
        result.put(RETRY_COUNT_KEY, replanRail.replanCount());
        result.put(UNMET_KEY, violations.stream()
                .map(v -> v.criterion() + ": " + v.reason())
                .toList());
        return result;
    }
}
