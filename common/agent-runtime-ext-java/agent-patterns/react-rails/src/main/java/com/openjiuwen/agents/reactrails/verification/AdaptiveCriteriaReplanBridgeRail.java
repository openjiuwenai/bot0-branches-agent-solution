/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.agents.reactrails.verification.AdaptiveCriteriaVerifier.Severity;
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
 * Severity-adaptive criteria verification bridge rail — replaces the binary PASS/FAIL
 * decision with a three-level steering strategy linked to the first-principles system prompt.
 *
 * <p><b>Mechanism</b> (same bytecode offsets as {@link CriteriaReplanBridgeRail}):
 * <ul>
 *   <li>{@code afterModelCall} fires inside {@code railedModelCall} (offset 687).</li>
 *   <li>{@code ctx.pushSteering(String)} queues string; consumed <b>next</b> iteration's
 *       {@code injectPendingSteering} (offset 675).</li>
 *   <li>{@code requestForceFinish} and {@code pushSteering} are independent — call both or just one.</li>
 *   <li>{@code consumeForceFinish} (offset 700) fires AFTER full {@code afterModelCall} returns.</li>
 * </ul>
 *
 * <p><b>Four exit paths</b> (vs three in the binary bridge):
 * <ol>
 *   <li><b>PASS</b> → {@code requestForceFinish(verified=true)} — lock correct terminal.</li>
 *   <li><b>NEAR_PASS</b> + budget remaining → light pushSteering (micro-adjust, no replan hint).</li>
 *   <li><b>WIDE_MISS</b> + budget remaining → strong pushSteering (re-approach, conditional replan hint).</li>
 *   <li><b>COMPLETE_MISS</b> or budget exhausted → {@code requestForceFinish(degraded=true)}.</li>
 * </ol>
 *
 * <p><b>Shared replan budget</b>: uses the same {@link ReplanRail#incrementAndCheckOverLimit()}
 * counter as LLM-initiated {@code __replan__} calls — total round budget is bounded.
 *
 * <p><b>Empirical anchors</b>:
 * <ul>
 *   <li>#2 First-principles self-check prompt → 0 replan: the base system prompt (Phase 1)
 *       avoids unnecessary replanning, making NEAR_PASS micro-adjustment the dominant path.</li>
 *   <li>#3 Conditional "如果你发现...可以调 __replan__" → 1 replan: WIDE_MISS hint uses
 *       same conditional framing for measured replan adoption.</li>
 *   <li>#1 Imperative "请先调用 __replan__" → 2 replan degrade: NEAR_PASS and WIDE_MISS paths
 *       explicitly avoid imperative replan framing.</li>
 *   <li>#4 pushSteering → retry → corrected: the mechanism is validated for WIDE_MISS cases.</li>
 * </ul>
 */
public class AdaptiveCriteriaReplanBridgeRail extends AgentRail {

    public static final String OUTPUT_KEY = "output";
    public static final String VERIFIED_KEY = "criteria_verified";
    public static final String RESULT_KEY = "criteria_result";
    public static final String DEGRADED_KEY = "degraded";
    public static final String UNMET_KEY = "unmet_criteria";
    public static final String RETRY_COUNT_KEY = "criteria_retry_count";
    public static final String SEVERITY_KEY = "criteria_severity";

    private final AdaptiveCriteriaVerifier verifier;
    private final List<String> successCriteria;
    private final List<String> decisionHistory = new ArrayList<>();
    private final ReplanRail replanRail;

    /**
     * @param verifier        the adaptive three-level verifier
     * @param successCriteria the criteria to check against the final output
     * @param replanRail      shared replan counter (budget overlap with LLM-initiated replan)
     */
    public AdaptiveCriteriaReplanBridgeRail(
            AdaptiveCriteriaVerifier verifier,
            List<String> successCriteria,
            ReplanRail replanRail) {
        this.verifier = verifier;
        this.successCriteria = List.copyOf(successCriteria);
        this.replanRail = replanRail;
    }

    /**
     * 模型回调钩子：终态答案走 adaptive verify → 四出口门，
     * 工具轮则累积决策历史。
     *
     * <p>四出口：
     * <ol>
     *   <li>PASS → forceFinish(verified=true)</li>
     *   <li>NEAR_PASS + budget → light pushSteering (微调)</li>
     *   <li>WIDE_MISS + budget → strong pushSteering (换方向)</li>
     *   <li>COMPLETE_MISS 或 budget 耗尽 → forceFinish(degraded=true)</li>
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
            // Final answer → verify → severity-adaptive gate
            String output = contentOf(msg);
            String historyStr = String.join(" | ", decisionHistory);
            List<Violation> violations = verifier.verify(successCriteria, output, historyStr);
            Severity sev = AdaptiveCriteriaVerifier.aggregate(violations);

            switch (sev) {
                case PASS -> ctx.requestForceFinish(verifiedResult(output));
                case COMPLETE_MISS -> ctx.requestForceFinish(degradedResult(output, violations, sev));
                case WIDE_MISS -> handleWideMiss(ctx, output, violations, sev);
                case NEAR_PASS -> handleNearPass(ctx, output, violations, sev);
            }
        } else {
            // Tool-call round → accumulate history for history-coverage checks
            accumulateToolCalls(msg);
        }
    }

    /**
     * NEAR_PASS → light pushSteering, no replan hint.
     * Empirical anchor #2: first-principles self-check eliminates unnecessary replanning.
     */
    private void handleNearPass(AgentCallbackContext ctx, String output,
                                List<Violation> violations, Severity sev) {
        if (replanRail.incrementAndCheckOverLimit()) {
            ctx.requestForceFinish(degradedResult(output, violations, sev));
        } else {
            ctx.pushSteering("前次回答接近通过验证，但以下标准未完全满足。"
                    + "当前路径正确，请微调补充遗漏部分：\n"
                    + violationDetails(violations)
                    + "\n只需修正未达标项，不要重写整个回答。"
                    + "如需放弃当前路径请调用 __replan__。");
        }
    }

    /**
     * WIDE_MISS → strong pushSteering with conditional replan hint.
     * Empirical anchor #3: conditional "如果...可以调" produces measured 1-replan adoption.
     */
    private void handleWideMiss(AgentCallbackContext ctx, String output,
                                List<Violation> violations, Severity sev) {
        if (replanRail.incrementAndCheckOverLimit()) {
            ctx.requestForceFinish(degradedResult(output, violations, sev));
        } else {
            ctx.pushSteering("前次回答与成功标准差距较大。请重新审视分析方法。\n"
                    + violationDetails(violations)
                    + "\n如果你的当前分析方法无法覆盖遗漏标准，请调用 __replan__ 调整策略。"
                    + "确保新策略能系统性地覆盖所有标准。");
        }
    }

    // ---- helpers (shared with CriteriaReplanBridgeRail where logic is identical) ----

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

    private static String violationDetails(List<Violation> violations) {
        return violations.stream()
                .map(v -> "- " + v.criterion() + ": " + v.reason())
                .collect(Collectors.joining("\n"));
    }

    // ---- result builders ----

    private static Map<String, Object> verifiedResult(String output) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(OUTPUT_KEY, output);
        result.put(VERIFIED_KEY, true);
        result.put(RESULT_KEY, "PASS");
        result.put(SEVERITY_KEY, "PASS");
        return result;
    }

    private Map<String, Object> degradedResult(String output, List<Violation> violations, Severity sev) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(OUTPUT_KEY, output);
        result.put(VERIFIED_KEY, false);
        result.put(RESULT_KEY, "FAIL");
        result.put(DEGRADED_KEY, true);
        result.put(RETRY_COUNT_KEY, replanRail.replanCount());
        result.put(SEVERITY_KEY, sev.name());
        result.put(UNMET_KEY, violations.stream()
                .map(v -> v.criterion() + ": " + v.reason())
                .toList());
        return result;
    }
}
