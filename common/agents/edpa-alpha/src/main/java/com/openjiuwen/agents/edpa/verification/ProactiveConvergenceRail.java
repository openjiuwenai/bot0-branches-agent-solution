/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.verification;

import com.openjiuwen.agents.edpa.kernel.EdpaKernel;
import com.openjiuwen.agents.edpa.kernel.ReplanAction;
import com.openjiuwen.agents.edpa.kernel.RootCause;
import com.openjiuwen.agents.reactrails.state.RailInvocationState;
import com.openjiuwen.agents.reactrails.types.Violation;
import com.openjiuwen.agents.reactrails.verification.CriteriaVerifier;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Proactive convergence verifier for EDPA's D(Decision) stage — the GEPA-converged
 * filler for the structural vacuum where {@code CriteriaReplanBridgeRail} only
 * verifies on the final answer (tool rounds merely accumulate history).
 *
 * <p><b>Signal</b> (NOT LLM self-report): on each tool round, run the shared
 * {@link CriteriaVerifier} against the <b>accumulated tool-result text</b> and
 * compute coverage = {@code 1 - violations / criteria}. If coverage <b>flatlines</b>
 * (delta ≈ 0 for {@code stallWindow} consecutive rounds) while below
 * {@code coverageCritical}, the trajectory is diverging.
 *
 * <p><b>Dispatch</b> (zero new sealed types — reuses the PEV kernel): on stall,
 * {@code RootCause.PlanOrAnswerError(∅)} → {@link EdpaKernel#toReplanAction} →
 * {@link ReplanAction.GlobalReplan} → {@link AgentCallbackContext#pushSteering}.
 * Only exercises the {@code GlobalReplan} arm (LocalReplan/AcceptPartial bypassed).
 *
 * <p><b>Edge-triggered</b>: fires once per stall episode, re-arms when coverage improves.
 *
 * <p><b>State isolation</b> (ba86176 pattern): mutable state (coverageHistory /
 * wasStalled / triggerCount / toolRoundCount) lives in {@link RailInvocationState}
 * keyed per rail instance, scoped to one invocation's {@link AgentCallbackContext}.
 * No synchronized needed — state is per-invocation, not shared. Cost: accessors
 * require ctx; after {@code agent.invoke} returns ctx is unreachable, so post-invoke
 * observation (triggerCount etc.) is not possible — bearing assertions rely on mock
 * unit tests that hold the ctx.
 *
 * <p><b>Honest boundary</b>: only meaningful with keyword-based criteria
 * ({@code RuleBasedCriteriaVerifier}) + bumped {@code maxIterations}. Coverage is a
 * coarse trajectory proxy. The {@code isFlatlined} delta test uses a {@code 1e-9}
 * dead band (assumes discrete coverage values).
 *
 * @since 2026-07
 */
public class ProactiveConvergenceRail extends AgentRail {
    /** Rail priority — ordered after safety/criteria rails so convergence only steers once core rails pass. */
    public static final int PRIORITY = 70;

    /** Default stall window: number of consecutive flat-coverage rounds that signal a stall. */
    public static final int DEFAULT_STALL_WINDOW = 2;

    /** Default coverage threshold below which a stall is treated as diverging. */
    public static final double DEFAULT_COVERAGE_CRITICAL = 0.34;

    private final CriteriaVerifier verifier;
    private final List<String> successCriteria;
    private final int stallWindow;
    private final double coverageCritical;
    private final String stateKey = RailInvocationState.newKey(ProactiveConvergenceRail.class);

    /**
     * Convenience constructor using default stall detection thresholds
     * ({@value #DEFAULT_STALL_WINDOW} round stall window, {@value #DEFAULT_COVERAGE_CRITICAL}
     * coverage critical).
     *
     * @param verifier         shared criteria verifier used to score accumulated tool results
     * @param successCriteria  non-empty success criteria the trajectory must converge toward
     * @throws IllegalArgumentException if {@code verifier} is {@code null}, or
     *         {@code successCriteria} is {@code null} or empty
     */
    public ProactiveConvergenceRail(CriteriaVerifier verifier, List<String> successCriteria) {
        this(verifier, successCriteria, DEFAULT_STALL_WINDOW, DEFAULT_COVERAGE_CRITICAL);
    }

    /**
     * Full constructor with explicit stall-detection thresholds.
     *
     * @param verifier          shared criteria verifier used to score accumulated tool results
     * @param successCriteria   non-empty success criteria the trajectory must converge toward
     * @param stallWindow       number of consecutive flat-coverage rounds that signal a stall
     *                          (must be {@code >= 1})
     * @param coverageCritical  coverage threshold below which a stall is treated as diverging
     * @throws IllegalArgumentException if {@code verifier} is {@code null};
     *         {@code successCriteria} is {@code null} or empty; or {@code stallWindow < 1}
     */
    public ProactiveConvergenceRail(CriteriaVerifier verifier, List<String> successCriteria, int stallWindow,
            double coverageCritical) {
        if (verifier == null) {
            throw new IllegalArgumentException("verifier must not be null");
        }
        if (successCriteria == null || successCriteria.isEmpty()) {
            throw new IllegalArgumentException("successCriteria must not be empty");
        }
        if (stallWindow < 1) {
            throw new IllegalArgumentException("stallWindow must be >= 1");
        }
        this.verifier = verifier;
        this.successCriteria = List.copyOf(successCriteria);
        this.stallWindow = stallWindow;
        this.coverageCritical = coverageCritical;
        setPriority(PRIORITY);
    }

    /**
     * Look up (creating on first access) the per-invocation mutable state for this rail.
     *
     * @param ctx the invocation context that scopes and owns the isolated state
     * @return the non-null {@link InvocationState} for this rail within {@code ctx}
     */
    private InvocationState state(AgentCallbackContext ctx) {
        return RailInvocationState.get(ctx, stateKey, InvocationState.class, InvocationState::new);
    }

    /**
     * Tool-round hook: compute coverage of accumulated tool results, detect stall,
     * push convergence steering on entry into a low-coverage stall.
     *
     * @param ctx per-invocation callback context carrying the model response and history
     */
    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }
        if (msg.getToolCalls() == null || msg.getToolCalls().isEmpty()) {
            return;
        }

        InvocationState s = state(ctx);
        s.toolRoundCount++;
        String accumulatedToolResults = extractAccumulatedToolResults(inputs.getMessages());
        List<Violation> violations = verifier.verify(successCriteria, "", accumulatedToolResults);
        double coverage = successCriteria.isEmpty()
                ? 1.0
                : 1.0 - (violations.size() / (double) successCriteria.size());

        s.coverageHistory.addLast(coverage);
        while (s.coverageHistory.size() > stallWindow + 1) {
            s.coverageHistory.pollFirst();
        }

        boolean stalledNow = isFlatlined(s) && coverage < coverageCritical;
        if (stalledNow && !s.wasStalled) {
            // Edge-triggered: fire once on stall entry, not every round.
            s.triggerCount++;
            RootCause cause = new RootCause.PlanOrAnswerError(Set.of());
            ReplanAction action = EdpaKernel.toReplanAction(cause, buildConvergenceFeedback(coverage), Set.of());
            if (action instanceof ReplanAction.GlobalReplan globalReplan) {
                ctx.pushSteering(globalReplan.feedback());
            }
        }
        s.wasStalled = stalledNow;
    }

    /**
     * Flatlined = the last {@code stallWindow} coverage deltas are all ≈ 0.
     *
     * @param s per-invocation state holding the rolling coverage history
     * @return {@code true} if coverage has been flat for {@code stallWindow} rounds
     */
    private boolean isFlatlined(InvocationState s) {
        if (s.coverageHistory.size() < stallWindow + 1) {
            return false;
        }
        // Read the trailing window into a primitive double[] to avoid per-element boxing.
        double[] recent = new double[stallWindow + 1];
        Iterator<Double> it = s.coverageHistory.descendingIterator();
        for (int i = stallWindow; i >= 0 && it.hasNext(); i--) {
            recent[i] = it.next();
        }
        for (int i = recent.length - 1; i > 0; i--) {
            if (Math.abs(recent[i] - recent[i - 1]) > 1e-9) {
                return false;
            }
        }
        return true;
    }

    /**
     * Build the Chinese-language steering prompt pushed into the trajectory on stall entry.
     *
     * @param coverage current coverage ratio to surface in the feedback text
     * @return the formatted convergence-steering feedback string
     */
    private String buildConvergenceFeedback(double coverage) {
        String coveragePct = String.format(Locale.ROOT, "%.2f", coverage);
        String thresholdPct = String.format(Locale.ROOT, "%.2f", coverageCritical);
        return "【主动收敛】轨迹连续 " + stallWindow
                + " 个工具轮未向成功标准收敛（当前覆盖率 " + coveragePct
                + "，低于阈值 " + thresholdPct + "）：已调用的工具未带来新的标准相关数据。"
                + "请调整方向——调用能覆盖缺失维度的工具，或调用 __replan__ 重定向。";
    }

    /**
     * Concatenate every {@link ToolMessage} content in history = all data fetched so far.
     *
     * @param messages the full message history (may be {@code null} or empty)
     * @return the space-joined tool-result text, or {@code ""} if none is available
     */
    private static String extractAccumulatedToolResults(List<Object> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object m : messages) {
            if (m instanceof ToolMessage tm) {
                String content = tm.getContentAsString();
                if (content != null && !content.isEmpty()) {
                    sb.append(content).append(' ');
                }
            }
        }
        return sb.toString();
    }

    // ---- test observation (bearing assertions; require ctx — state isolation) ----
    // 注：状态隔离后访问器需 ctx。invoke 返回后 ctx 不可达，post-invoke 观察不可能；
    // 承重靠 mock 单测（持有 ctx）。e2e 的 post-invoke triggerCount() 观察已移除。

    /**
     * Test-observation accessor: snapshot of the rolling coverage history.
     *
     * @param ctx the invocation context that owns the isolated rail state
     * @return a defensive copy of the coverage history (oldest first); empty if no rounds ran
     */
    public Deque<Double> coverageHistory(AgentCallbackContext ctx) {
        return new ArrayDeque<>(state(ctx).coverageHistory);
    }

    /**
     * Test-observation accessor: how many stall entries have fired this invocation.
     *
     * @param ctx the invocation context that owns the isolated rail state
     * @return number of edge-triggered convergence-steering dispatches that have occurred
     */
    public int triggerCount(AgentCallbackContext ctx) {
        return state(ctx).triggerCount;
    }

    /**
     * Test-observation accessor: how many tool rounds have been observed this invocation.
     *
     * @param ctx the invocation context that owns the isolated rail state
     * @return number of tool-bearing model responses processed by this rail
     */
    public int toolRoundCount(AgentCallbackContext ctx) {
        return state(ctx).toolRoundCount;
    }

    /**
     * Test-observation accessor: whether the most recent round was classified as stalled.
     *
     * @param ctx the invocation context that owns the isolated rail state
     * @return {@code true} if the latest round entered (or remained in) a low-coverage stall
     */
    public boolean wasStalled(AgentCallbackContext ctx) {
        return state(ctx).wasStalled;
    }

    /**
     * Per-invocation mutable state, isolated via {@link RailInvocationState} (ba86176 pattern).
     * One instance per rail per {@link AgentCallbackContext}; not shared across invocations.
     *
     * @since 2026-07
     */
    private static final class InvocationState {
        private final Deque<Double> coverageHistory = new ArrayDeque<>();
        private boolean wasStalled = false;
        private int triggerCount = 0;
        private int toolRoundCount = 0;
    }
}
