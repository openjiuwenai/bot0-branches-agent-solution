/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel;
import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel.InjectionMode;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * LangChain-style PreCompletionChecklist + AgentScope 2.0 PLAN/BUILD phase guard.
 *
 * <p>Dual-hook design:
 * <ul>
 *   <li>{@link #beforeModelCall} (priority=80) — injects phase-aware guardrails via
 *       {@link SystemPromptInjectingModel}'s static channel BEFORE every LLM call.</li>
 *   <li>{@link #afterModelCall} (priority=80) — updates internal metrics for the
 *       next checklist decision (call count, output diversity, tool coverage).</li>
 * </ul>
 *
 * <p>Checklist items (decided per-iteration in beforeModelCall):
 * <ol>
 *   <li><b>Iteration &lt; planMaxRounds</b> → {@code PLAN_MODE}: exploratory system prompt,
 *       optionally with a "use tools" guardrail if prior round produced zero tool calls.</li>
 *   <li><b>Iteration &gt;= planMaxRounds</b> → {@code BUILD_MODE}: convergent system prompt,
 *       optionally with coverage guardrails if prior verify hints were consumed.</li>
 *   <li><b>Stagnation detected</b> (peek from ctx.getExtra("stagnation_count")) → inject
 *       BREAK_LOOP override.</li>
 * </ol>
 *
 * <p>Zero forceFinish — this rail only injects. Decision authority stays with
 * {@link CriteriaReplanBridgeRail} (afterModelCall).
 *
 * <p>Zero pushSteering — this rail communicates through EnforcingModel's static channel.
 *
 * <p>IFF 契约:
 * <ul>
 *   <li>Strip setPhaseOverride in beforeModelCall → no guardrail injected during
 *       first round → LLM may ignore tools → RED (mock-assert tool call count = 0).</li>
 *   <li>Strip setInjectionMode(PLAN_MODE) → system prompt unchanged → LLM may skip
 *       divergent exploration → RED (mock-assert exploration keywords in output).</li>
 * </ul>
 */
public class PreCompletionChecklistRail extends AgentRail {

    /** Priority: 80 — fires after VotingCriticVerifierRail (100)
     *  and before StagnationDetectionRail (50) in afterModelCall.
     *  Same priority applies to beforeModelCall (no other rail uses it today). */
    private static final int PRIORITY = 80;

    /** Max iterations in PLAN phase before switching to BUILD. */
    private final int planMaxRounds;

    // ---- Internal state ----

    /** Current iteration count (tracked by afterModelCall). */
    private int callCount = 0;

    /** Rolling window of output content prefixes (for observation, not used for detection). */
    private final List<String> outputHashes = new ArrayList<>();
    private static final int OUTPUT_HASH_WINDOW = 5;

    /** Set of tool names ever called (for diversity check). */
    private final Set<String> toolNamesCalled = new LinkedHashSet<>();

    /** Whether the previous iteration produced a final answer (no tool calls). */
    private boolean previousWasFinal = false;

    // ---- Construction ----

    /**
     * @param planMaxRounds max iterations for PLAN phase before BUILD takes over.
     *                      Must be >= 1.
     */
    public PreCompletionChecklistRail(int planMaxRounds) {
        if (planMaxRounds < 1) {
            throw new IllegalArgumentException(
                    "planMaxRounds must be >= 1: " + planMaxRounds);
        }
        this.planMaxRounds = planMaxRounds;
        setPriority(PRIORITY);
    }

    // ---- Test observation points ----

    public synchronized int getCallCount() { return callCount; }
    public synchronized int getToolDiversity() { return toolNamesCalled.size(); }
    public synchronized List<String> getOutputHashes() {
        return List.copyOf(outputHashes);
    }
    public int getPlanMaxRounds() { return planMaxRounds; }

    // ============================================================
    // beforeModelCall: INJECT GUARDRAILS
    // ============================================================

    /**
     * Runs before every model invocation.
     *
     * <p>Decides what guardrail to inject based on current state:
     * <ol>
     *   <li>First few iterations (callCount < planMaxRounds): PLAN_MODE
     *       — exploratory system prompt, with optional tool-usage guardrail.</li>
     *   <li>Later iterations (callCount >= planMaxRounds): BUILD_MODE
     *       — convergent system prompt with coverage guardrail.</li>
     *   <li>Stagnancy detected via ctx.getExtra("stagnation_detected"):
     *       injects BREAK_LOOP override regardless of phase.</li>
     * </ol>
     */
    @Override
    public synchronized void beforeModelCall(AgentCallbackContext ctx) {
        // First call: no prior iteration data yet, just set PLAN_MODE
        if (callCount == 0) {
            SystemPromptInjectingModel.setInjectionMode(InjectionMode.PLAN_MODE);
            return;
        }

        // [1] Check stagnation from ctx.getExtra (written by StagnationDetectionRail)
        Object stagnationRaw = ctx.getExtra().get("stagnation_detected");
        if (stagnationRaw instanceof Boolean stagnated && stagnated) {
            SystemPromptInjectingModel.setPhaseOverride(
                    "BREAK_STAGNATION: The system detects that your output or "
                    + "tool-call pattern has become repetitive. Change your approach "
                    + "entirely — use a different set of tools or reframe the problem.");
            return;
        }

        // [2] Phase-based guardrail
        if (callCount < planMaxRounds) {
            // PLAN phase: ensure PLAN_MODE, optionally add tool-usage hint
            if (SystemPromptInjectingModel.getInjectionMode() != InjectionMode.PLAN_MODE) {
                SystemPromptInjectingModel.setInjectionMode(InjectionMode.PLAN_MODE);
            }
            // If previous iteration was pure text (no tool calls), remind to use tools
            if (!toolNamesCalled.isEmpty() && previousWasFinal) {
                SystemPromptInjectingModel.setPhaseOverride(
                        "REMINDER: Your current approach is text-only. "
                        + "Use available tools to fetch real data or validate assumptions.");
            }
        } else {
            // BUILD phase: switch to focused execution
            if (SystemPromptInjectingModel.getInjectionMode() != InjectionMode.BUILD_MODE) {
                SystemPromptInjectingModel.setInjectionMode(InjectionMode.BUILD_MODE);
            }
            // Low tool diversity hint
            if (toolNamesCalled.size() <= 1 && callCount > 2) {
                SystemPromptInjectingModel.setPhaseOverride(
                        "COVERAGE: You are using very few tool types. "
                        + "If the current approach is not making progress, "
                        + "try a different tool or call __replan__.");
            }
        }
    }

    // ============================================================
    // afterModelCall: UPDATE METRICS
    // ============================================================

    /**
     * Updates internal metrics for the next beforeModelCall decision.
     *
     * <p>Tracks: call count, output content hashes, tool diversity.
     * Does NOT call pushSteering or requestForceFinish.
     */
    @Override
    public synchronized void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }

        callCount++;

        // Track output hash for stagnancy observation
        String content = msg.getContentAsString();
        if (content != null && !content.isEmpty()) {
            String hash = content.length() > 200
                    ? content.substring(0, 200)
                    : content;
            outputHashes.add(hash);
            while (outputHashes.size() > OUTPUT_HASH_WINDOW) {
                outputHashes.remove(0);
            }
        }

        // Track tool names called
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            previousWasFinal = false;
            for (ToolCall tc : msg.getToolCalls()) {
                if (tc.getName() != null) {
                    toolNamesCalled.add(tc.getName());
                }
            }
        } else {
            previousWasFinal = true;
        }
    }
}
