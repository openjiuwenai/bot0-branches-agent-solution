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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Criteria verification bridge rail — instead of immediate degrade on verify
 * failure, injects steering correction and retries. Only forceFinish(degraded)
 * after maxRetries exhausted.
 *
 * <p>Drop-in replacement for {@link CriteriaVerificationRail}
 * — same constructor signature + ReplanRail for shared counter.
 *
 * <p><b>Mechanism</b> (bytecode-verified on agent-core-java 0.1.12):
 * <ul>
 *   <li>afterModelCall fires INSIDE railedModelCall (callModel offset 687).</li>
 *   <li>ctx.pushSteering(String) queues string; consumed NEXT iteration's
 *       injectPendingSteering (offset 675) → UserMessage → ModelContext.</li>
 *   <li>requestForceFinish and pushSteering are independent — call both or just one.</li>
 *   <li>consumeForceFinish(700) fires AFTER full afterModelCall returns.</li>
 * </ul>
 *
 * <p>Three exit paths:
 * <ul>
 *   <li><b>verify pass</b> → requestForceFinish(verified=true) — lock correct terminal state.</li>
 *   <li><b>verify fail + retries remaining</b> → pushSteering(correctionHint),
 *       no forceFinish. Next loop iteration injects the steering into LLM context for retry.</li>
 *   <li><b>verify fail + retries exhausted</b> → requestForceFinish(degraded=true, unmet=violations)
 *       — honest degrade terminal.</li>
 * </ul>
 */
public class CriteriaReplanBridgeRail extends AgentRail {

    public static final String OUTPUT_KEY = "output";
    public static final String VERIFIED_KEY = "criteria_verified";
    public static final String RESULT_KEY = "criteria_result";
    public static final String DEGRADED_KEY = "degraded";
    public static final String UNMET_KEY = "unmet_criteria";
    public static final String RETRY_COUNT_KEY = "criteria_retry_count";

    private final CriteriaVerifier verifier;
    private final List<String> successCriteria;
    private final List<String> decisionHistory = new ArrayList<>();
    private final ReplanRail replanRail;

    /**
     * @param verifier         the external-judge verifier
     * @param successCriteria  the criteria to check against the final output
     * @param replanRail       shared replan counter (used for overlap budget)
     */
    public CriteriaReplanBridgeRail(CriteriaVerifier verifier,
                                    List<String> successCriteria,
                                    ReplanRail replanRail) {
        this.verifier = verifier;
        this.successCriteria = List.copyOf(successCriteria);
        this.replanRail = replanRail;
    }

    /**
     * 模型回调钩子：终态答案走 verify → 三出口门，工具轮则累积决策历史。
     *
     * <p>三出口：
     * <ol>
     *   <li>verify pass → forceFinish(verified=true)</li>
     *   <li>verify fail + under limit → pushSteering(correction), 不 forceFinish</li>
     *   <li>verify fail + over limit → forceFinish(degraded=true)</li>
     * </ol>
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
            // Final answer → verify → triple-exit gate
            String output = contentOf(msg);
            String historyStr = String.join(" | ", decisionHistory);
            List<Violation> violations = verifier.verify(successCriteria, output, historyStr);

            if (violations.isEmpty()) {
                // Exit 1: Pass → lock verified terminal
                ctx.requestForceFinish(verifiedResult(output));
            } else {
                // Exit 2 & 3: Fail → delegate counting to ReplanRail (shared budget)
                boolean overLimit = replanRail.incrementAndCheckOverLimit();
                if (overLimit) {
                    // Exit 3: Retries exhausted → forceFinish degraded
                    ctx.requestForceFinish(degradedResult(output, violations));
                } else {
                    // Exit 2: Retries remain → push steering hint, continue loop
                    ctx.pushSteering(buildCorrectionHint(violations));
                }
            }
        } else {
            // Tool-call round → accumulate history
            accumulateToolCalls(msg);
        }
    }

/**
     * IFF 范式：剥离 gradient metadata → 降级到通用纠正提示 → LLM 看不到精确定位的缺失维度 → RED。
     *
     * <p>当 violations 携带 gradient metadata（来自 {@link GradientVerifier}）时，
     * 生成维度级精确定位的纠正提示（区分已覆盖/缺失维度）；否则回退到通用提示格式。
     */
    private static String buildCorrectionHint(List<Violation> violations) {
        boolean hasGradient = violations.stream()
                .anyMatch(v -> v.metadata() != null && v.metadata().containsKey("isPartial"));
        if (hasGradient) {
            return buildGradientHint(violations);
        }
        // Fallback: standard hint (backward compat with RuleBasedCriteriaVerifier / AdaptiveCriteriaVerifier / MultiPassBestOfKVerifier)
        // Note: no __replan__ hint — empirical data shows imperative replan framing
        // (data point #1: deepseek-v4-flash "请先调用 __replan__" → 2 replans + degrade)
        // induces unnecessary replan. MultiPassBestOfKVerifier only signals replan when
        // BOTH dimensions < FAIL_THRESHOLD, through the violation reason itself.
        return "您的回答未能满足以下成功标准，请据此修改后重新回答：\n"
            + violations.stream()
                .map(v -> "- " + v.criterion() + ": " + v.reason())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 梯度纠正提示 — 利用 Violation.metadata 中的梯度信息生成精确定位的 steering。
     *
     * <p>Three formats based on metadata:
     * <ul>
     *   <li>isPartial=true → "已覆盖: X, 请补充: Y" (最小化 LLM 重新开始倾向)</li>
     *   <li>isPartial=false + allDimensions → "需要覆盖的维度: X、Y、Z"</li>
     *   <li>fallback → 逐条显示 criterion: reason</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    private static String buildGradientHint(List<Violation> violations) {
        StringBuilder sb = new StringBuilder("您的回答未能完全满足以下标准：\n");
        for (Violation v : violations) {
            Map<String, Object> meta = v.metadata();
            sb.append("· ").append(v.criterion()).append("\n");
            if (meta != null && (boolean) meta.getOrDefault("isPartial", false)) {
                List<String> covered = (List<String>) meta.getOrDefault("covered", List.of());
                List<String> missing = (List<String>) meta.getOrDefault("missing", List.of());
                if (!covered.isEmpty()) {
                    sb.append("  ✅ 已覆盖: ").append(String.join("、", covered)).append("\n");
                }
                sb.append("  ❌ 请补充: ").append(String.join("、", missing)).append("\n");
            } else if (meta != null && meta.containsKey("allDimensions")) {
                List<String> allDims = (List<String>) meta.get("allDimensions");
                sb.append("  需要覆盖的维度: ").append(String.join("、", allDims)).append("\n");
            } else {
                sb.append("  ").append(v.reason()).append("\n");
            }
        }
        sb.append("如需调整当前分析方法或方向，请调用 __replan__ 工具。");
        return sb.toString();
    }

    private static boolean isFinalAnswer(AssistantMessage msg) {
        return msg.getToolCalls() == null || msg.getToolCalls().isEmpty();
    }

    private void accumulateToolCalls(AssistantMessage msg) {
        if (msg.getToolCalls() == null) {
            return;
        }
        for (ToolCall tc : msg.getToolCalls()) {
            decisionHistory.add(tc.getName() + "(" + tc.getArguments() + ")");
        }
    }

    private static String contentOf(AssistantMessage msg) {
        String content = msg.getContentAsString();
        return content != null ? content : "";
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
