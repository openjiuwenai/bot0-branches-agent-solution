/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.enforcing.PromptInjectionState;
import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel;
import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel.InjectionMode;
import com.openjiuwen.agents.reactrails.state.RailInvocationState;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * LangChain-style PreCompletionChecklist + AgentScope 2.0 PLAN/BUILD phase guard.
 *
 * <p>Dual-hook design:
 * <ul>
 *   <li>{@link #beforeModelCall} (priority=80) — injects phase-aware guardrails via
 *       the target {@link SystemPromptInjectingModel}'s state channel BEFORE every LLM call.</li>
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
 *   <li><b>Stagnation detected</b> (pending model override or an explicit callback-extra signal)
 *       → preserve/inject the BREAK_LOOP override.</li>
 * </ol>
 *
 * <p>Zero forceFinish — this rail only injects. Decision authority stays with
 * {@link CriteriaReplanBridgeRail} (afterModelCall).
 *
 * <p>Zero pushSteering — this rail communicates through the target model's explicit state channel.
 *
 * <p>IFF 契约:
 * <ul>
 *   <li>Strip setPhaseOverride in beforeModelCall → no guardrail injected during
 *       first round → LLM may ignore tools → RED (mock-assert tool call count = 0).</li>
 *   <li>Strip setInjectionMode(PLAN_MODE) → system prompt unchanged → LLM may skip
 *       divergent exploration → RED (mock-assert exploration keywords in output).</li>
 * </ul>
 *
 * @since 2026-07
 */
public class PreCompletionChecklistRail extends AgentRail {
    /**
     * Priority: 80 — fires after VotingCriticVerifierRail (100) and before
     * StagnationDetectionRail (50) in afterModelCall. Same priority applies to
     * beforeModelCall (no other rail uses it today).
     */
    private static final int PRIORITY = 80;

    private static final int OUTPUT_HASH_WINDOW = 5;

    /**
     * Max iterations in PLAN phase before switching to BUILD.
     */
    private final int planMaxRounds;
    private final PromptInjectionState injectionState;
    private final String stateKey = RailInvocationState.newKey(PreCompletionChecklistRail.class);

    // ---- Construction ----

    /**
     * Creates a checklist rail with a bounded PLAN phase.
     *
     * @param planMaxRounds max iterations for PLAN phase before BUILD takes over; must be >= 1
     * @param injectionState target model's prompt injection state
     */
    public PreCompletionChecklistRail(int planMaxRounds, PromptInjectionState injectionState) {
        if (planMaxRounds < 1) {
            throw new IllegalArgumentException("planMaxRounds must be >= 1: " + planMaxRounds);
        }
        this.planMaxRounds = planMaxRounds;
        this.injectionState = Objects.requireNonNull(injectionState, "injectionState");
        setPriority(PRIORITY);
    }

    // ---- Test observation points ----

    /**
     * Number of observed model-call hooks.
     *
     * @return total hook call count
     */
    public int getCallCount(AgentCallbackContext ctx) {
        return state(ctx).callCount;
    }

    /**
     * Number of distinct tool names called.
     *
     * @return distinct tool name count
     */
    public int getToolDiversity(AgentCallbackContext ctx) {
        return state(ctx).toolNamesCalled.size();
    }

    /**
     * Output hash observation window.
     *
     * @return copied output hash list
     */
    public List<String> getOutputHashes(AgentCallbackContext ctx) {
        return List.copyOf(state(ctx).outputHashes);
    }

    /**
     * Gets the configured PLAN phase limit.
     *
     * @return maximum PLAN phase rounds
     */
    public int getPlanMaxRounds() {
        return planMaxRounds;
    }

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
     *
     * @param ctx callback context for the pending model call
     */
    @Override
    public void beforeModelCall(AgentCallbackContext ctx) {
        InvocationState state = state(ctx);
        // First call: no prior iteration data yet, just set PLAN_MODE
        if (state.callCount == 0) {
            injectionState.setPhaseOverride(null);
            injectionState.setMode(InjectionMode.PLAN_MODE);
            return;
        }

        // [1] Check stagnation from ctx.getExtra (written by StagnationDetectionRail)
        Object stagnationRaw = ctx.getExtra().get("stagnation_detected");
        if (stagnationRaw instanceof Boolean isStagnated && isStagnated) {
            injectionState.setPhaseOverride("BREAK_STAGNATION: The system detects that your output or "
                    + "tool-call pattern has become repetitive. Change your approach "
                    + "entirely — use a different set of tools or reframe the problem.");
            return;
        }

        // [2] Phase-based guardrail
        if (state.callCount < planMaxRounds) {
            // PLAN phase: ensure PLAN_MODE, optionally add tool-usage hint
            if (injectionState.getMode() != InjectionMode.PLAN_MODE) {
                injectionState.setMode(InjectionMode.PLAN_MODE);
            }
            if (injectionState.peekPhaseOverride() != null) {
                return;
            }
            // If previous iteration was pure text (no tool calls), remind to use tools
            if (state.hasPreviousFinalAnswer) {
                injectionState.setPhaseOverride("REMINDER: Your current approach is text-only. "
                        + "Use available tools to fetch real data or validate assumptions.");
            }
        } else {
            // BUILD phase: switch to focused execution
            if (injectionState.getMode() != InjectionMode.BUILD_MODE) {
                injectionState.setMode(InjectionMode.BUILD_MODE);
            }
            if (injectionState.peekPhaseOverride() != null) {
                return;
            }
            // Low tool diversity hint
            if (state.toolNamesCalled.size() <= 1 && state.callCount > 2) {
                injectionState.setPhaseOverride("COVERAGE: You are using very few tool types. "
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
     *
     * @param ctx callback context carrying model-call inputs
     */
    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }

        InvocationState state = state(ctx);
        state.callCount++;

        // Track output hash for stagnancy observation
        String content = msg.getContentAsString();
        if (content != null && !content.isEmpty()) {
            String hash = content.length() > 200 ? content.substring(0, 200) : content;
            state.outputHashes.add(hash);
            while (state.outputHashes.size() > OUTPUT_HASH_WINDOW) {
                state.outputHashes.remove(0);
            }
        }

        // Track tool names called
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            state.hasPreviousFinalAnswer = false;
            for (ToolCall tc : msg.getToolCalls()) {
                if (tc.getName() != null) {
                    state.toolNamesCalled.add(tc.getName());
                }
            }
        } else {
            state.hasPreviousFinalAnswer = true;
        }
    }

    private InvocationState state(AgentCallbackContext ctx) {
        return RailInvocationState.get(ctx, stateKey, InvocationState.class, InvocationState::new);
    }

    private static final class InvocationState {
        private int callCount;
        private final List<String> outputHashes = new ArrayList<>();
        private final Set<String> toolNamesCalled = new LinkedHashSet<>();
        private boolean hasPreviousFinalAnswer;
    }
}
