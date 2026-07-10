/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.replan;

import com.openjiuwen.agents.pev.kernel.PevKernel;
import com.openjiuwen.agents.pev.kernel.ReplanAction;
import com.openjiuwen.agents.pev.kernel.RootCause;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * PEV-gated replan rail for ReActAgent — replaces {@link ReplanRail}.
 *
 * <p>Like the original {@link ReplanRail}, this hooks {@code afterModelCall} and detects
 * {@code __replan__} tool calls (via {@link ReplanTool}). Unlike the original, it replaces
 * the simple count-and-escalate logic with PEV kernel dispatch:
 *
 * <ol>
 *   <li><b>Diagnose</b> ReActAgent signals (tool failure correlation, LLM self-report) into
 *       a {@link RootCause} — replaces {@code PevKernel.diagnoseRootCause} (which is PEV-agent
 *       specific, relying on {@code VerifyResult} and {@code NodeResult} types unavailable to
 *       ReActAgent).</li>
 *   <li><b>Dispatch</b> via {@link PevKernel#toReplanAction} — sealed switch, compiler-enforced
 *       exhaustive over the three outcomes:
 *       <ul>
 *         <li>{@link ReplanAction.GlobalReplan} / {@link ReplanAction.LocalReplan} → push
 *             steering (consumed by injectPendingSteering on the next iteration, offsets
 *             675→UserMessage→ModelContext). Steering survives the tool-call round and reaches
 *             the LLM on the next callModel.</li>
 *         <li>{@link ReplanAction.AcceptPartial} → requestForceFinish(degraded) — honest
 *             terminal, stops the loop.</li>
 *       </ul>
 *   </li>
 *   <li><b>Over-limit guard</b>: when {@code replanCount > maxReplan}, forceFinish directly
 *       without PEV dispatch — same semantics as the original.</li>
 * </ol>
 *
 * <h3>Steering vs forceFinish (bytecode-verified)</h3>
 *
 * <p>On agent-core-java 0.1.12 jar, ReActAgent.invoke has three consumeForceFinish sites
 * (offsets 225/700/878) and one injectPendingSteering site (offset 675). Steering is consumed
 * at iteration boundaries:
 * <pre>
 *   Iteration N:  tool round (__replan__ call) → afterModelCall → pushSteering
 *   Iteration N+1: injectPendingSteering → drainSteering → UserMessage → LLM sees guidance
 * </pre>
 * Steering requires at least one more model-call iteration. Verify failures land on a final
 * answer (no tool calls → loop terminates at offset 844/857) — steering can't redirect a
 * terminated loop. For verify failures, only AcceptPartial/forceFinish is viable.
 * This rail handles the __replan__-has-tool-calls path; CriteriaVerificationRail handles the
 * verify-failure path with forceFinish (existing behavior).
 *
 * <h3>Honest boundary</h3>
 * <ul>
 *   <li>RootCause correlation is single-thread best-effort via {@link #recordToolFailure}.
 *       The ReActAgent loop does not guarantee that onToolException fires before afterModelCall
 *       in the same iteration (they occupy different bytecode offsets).</li>
 *   <li>PerceptionUnreliable is not diagnosed here — no verifier is involved in the __replan__
 *       path. The dispatch function still handles it if called, but it's structurally deferred.</li>
 * </ul>

  * @since 2026-07*/
public class PevReplanRail extends AgentRail {

    public static final String REPLAN_EXCEEDED_KEY = "replan_exceeded";
    public static final String DEGRADED_KEY = "degraded";
    public static final String REPLAN_COUNT_KEY = "replan_count";
    public static final String MAX_REPLAN_KEY = "max_replan";

    private final int maxReplan;
    private int replanCount = 0;
    private final Set<String> recentToolFailureNodes = new LinkedHashSet<>();

    /** 指定最大 replan 次数构造 rail。 */
    public PevReplanRail(int maxReplan) {
        this.maxReplan = maxReplan;
    }

    /** 默认构造，最大 replan 次数取 2。 */
    public PevReplanRail() {
        this(2);
    }

    /** Current replan count (test observation). */
    public synchronized int replanCount() {
        return replanCount;
    }

    /** Recent tool failure nodes (test observation). */
    public synchronized List<String> recentToolFailureNodes() {
        return List.copyOf(recentToolFailureNodes);
    }

    /**
     * Record a tool failure for cross-correlation in diagnose. Called externally (e.g. from
     * {@link com.openjiuwen.agents.reactrails.selfheal.RootCauseRail}) to register device
     * failures that should tilt the next __replan__ diagnosis toward DeviceFailure.
     */
    public synchronized void recordToolFailure(String toolName) {
        recentToolFailureNodes.add(toolName);
    }

    /**
     * 模型回调钩子：检测 __replan__ 调用 → PEV 诊断 + dispatch.
     *
     * <p>控制流（字节码验证 agent-core-java 0.1.12）：
     * <ul>
     *   <li>有 __replan__ 工具调用 → 工具会执行 → 循环继续 → next iteration 的
     *       injectPendingSteering 会消费 steering → 下一轮模型调用 LLM 看到 steering</li>
     *   <li>replan 超限 → 直接 forceFinish(degraded) — 不投 steering（无意义，循环终止）
     *   <li>PEV dispatch GlobalReplan → pushSteering（不 forceFinish）</li>
     *   <li>PEV dispatch AcceptPartial → forceFinish（不投 steering）</li>
     * </ul>
     */
    @Override
    public synchronized void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs)) {
            return;
        }
        ModelCallInputs inputs = (ModelCallInputs) ctx.getInputs();
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }
        if (msg.getToolCalls() == null || msg.getToolCalls().isEmpty()) {
            return;
        }

        Optional<ToolCall> maybeReplanCall = findReplanCall(msg);
        if (maybeReplanCall.isEmpty()) {
            return;
        }
        ToolCall replanCall = maybeReplanCall.get();

        replanCount++;
        if (replanCount > maxReplan) {
            ctx.requestForceFinish(degradedResult("Replan 次数已达上限 " + maxReplan));
            return;
        }

        // Extract LLM-provided reason from __replan__ tool call arguments
        String reason = extractJsonStringField(replanCall.getArguments(), ReplanTool.ARG_REPLAN_REASON);
        if (reason == null || reason.isEmpty()) {
            reason = "LLM 发起策略调整";
        }
        String newApproach = extractJsonStringField(replanCall.getArguments(), ReplanTool.ARG_NEW_APPROACH);

        // ReActAgent-specific diagnosis (replaces PevKernel.diagnoseRootCause)
        RootCause cause = diagnose(reason, newApproach);

        // PEV dispatch — pure function, compiler-enforced exhaustive
        Set<String> failedNodes = cause instanceof RootCause.PlanOrAnswerError pe ? pe.nodes() : Set.of();
        ReplanAction action = PevKernel.toReplanAction(cause, reason, failedNodes);

        // Execute dispatch
        switch (action) {
            case ReplanAction.GlobalReplan gr -> {
                String steering = "【全局重规划】" + gr.feedback() + "。请基于以下指导重新制定方案：" + newApproach;
                ctx.pushSteering(steering);
            }
            case ReplanAction.LocalReplan lr -> {
                String steering = "【局部修正】节点 " + lr.failedNodes() + " 需要重新处理。修正提示：" + lr.feedback();
                ctx.pushSteering(steering);
            }
            case ReplanAction.AcceptPartial ap -> {
                ctx.requestForceFinish(degradedResult(ap.reason()));
            }
        }
    }

    /**
     * ReActAgent-specific diagnosis: map agent signals to {@link RootCause}.
     *
     * <p>For {@code __replan__} tool calls, the baseline signal is PlanOrAnswerError — the LLM
     * self-perceives that its current approach is flawed and requests redirection. If recent
     * tool failures (registered via {@link #recordToolFailure}) correlate with the LLM's stated
     * reason, the diagnosis tilts to DeviceFailure — a broken tool cannot be healed by replanning.
     */
    private RootCause diagnose(String reason, String newApproach) {
        // If recent tool failures exist AND reason mentions the failed tools → DeviceFailure
        if (!recentToolFailureNodes.isEmpty() && recentToolFailureNodes.stream().anyMatch(reason::contains)) {
            return new RootCause.DeviceFailure(new LinkedHashSet<>(recentToolFailureNodes));
        }
        // LLM self-initiated replan → PlanOrAnswerError with empty nodes → GlobalReplan
        return new RootCause.PlanOrAnswerError(Set.of());
    }

    private static Optional<ToolCall> findReplanCall(AssistantMessage msg) {
        for (ToolCall tc : msg.getToolCalls()) {
            if (ReplanTool.TOOL_NAME.equals(tc.getName())) {
                return Optional.of(tc);
            }
        }
        return Optional.empty();
    }

    /**
     * Extract a JSON string field value from a plain JSON object string.
     *
     * <p>Handles the basic case {@code {"field":"value"}} without Jackson dependency.
     * Escaped quotes are not handled (not needed for LLM-generated tool call args in practice).
     */
    private static String extractJsonStringField(String json, String field) {
        if (json == null || json.isBlank())
            return "";
        String search = "\"" + field + "\":\"";
        int start = json.indexOf(search);
        if (start < 0)
            return "";
        start += search.length();
        int end = json.indexOf('"', start);
        if (end < 0)
            return "";
        return json.substring(start, end);
    }

    private Map<String, Object> degradedResult(String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(REPLAN_EXCEEDED_KEY, true);
        result.put(DEGRADED_KEY, true);
        result.put(REPLAN_COUNT_KEY, replanCount);
        result.put(MAX_REPLAN_KEY, maxReplan);
        result.put("output", message);
        return result;
    }
}
