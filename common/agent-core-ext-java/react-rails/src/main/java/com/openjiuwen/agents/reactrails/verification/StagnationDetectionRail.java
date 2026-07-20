/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.verification;

import com.openjiuwen.agents.reactrails.enforcing.PromptInjectionState;
import com.openjiuwen.agents.reactrails.observability.ObservingRail;
import com.openjiuwen.agents.reactrails.observability.RailEvent;
import com.openjiuwen.agents.reactrails.observability.RailTelemetry;
import com.openjiuwen.agents.reactrails.state.RailInvocationState;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * LangChain-style Loop Detection Rail — detects output stagnation and tool-call
 * cycles, breaks them with targeted steering, and forceFinishes if persistent.
 *
 * <p><b>Dual-hook design</b> (same consumption pattern as
 * {@link com.openjiuwen.agents.reactrails.selfheal.RootCauseRail}):
 * <ul>
 *   <li>{@link #afterModelCall} — detects output repetition (same final answer)
 *       and tool-call cycles (same tool sequence). Pushes brake steering when
 *       a pattern repeats.</li>
 *   <li>{@link #onToolException} — records tool failures for context. Repeated
 *       failures on the same tool are a stagnation signal.</li>
 * </ul>
 *
 * <p><b>Stagnation detection — output hash</b>:
 * Tracks the last N final-answer content hashes (via sliding window of size
 * {@link #OUTPUT_HISTORY_SIZE}). When the same output hash appears
 * {@link #MAX_OUTPUT_REPEATS} consecutive times → brake steering is injected
 * on the next beforeModelCall / via pushSteering.
 *
 * <p><b>Stagnation detection — tool cycle</b>:
 * Tracks the last M tool-call signatures (tool-name sequence). When the same
 * sequence repeats {@link #MAX_TOOL_CYCLE_REPEATS} times → brake steering.
 *
 * <p><b>Escalation</b>: after {@link #MAX_STAGNATIONS} total triggers, the rail
 * calls {@code ctx.requestForceFinish(degraded)} — honest terminal, stops the loop.
 *
 * <p><b>Priority</b>: MEDIUM (50) — fires after VotingCriticVerifierRail (100)
 * and before CriteriaReplanBridgeRail (0). This lets the critic's data inform
 * the stagnation analysis, while the bridge rail still has final say on forceFinish.
 *
 * <p><b>Phase override communication</b>:
 * When stagnation is detected, this rail calls
 * the target model's {@link PromptInjectionState#setPhaseOverride(String)} to inject a
 * loop-break prompt on the next model invocation. The override is consumed
 * by {@code SystemPromptInjectingModel.invoke()} in USER_MESSAGE_INJECT mode.
 *
 * <p><b>IFF 契约</b>:
 * <ul>
 *   <li>Strip {@code consecutiveOutputRepeats++} → never triggers brake → test RED</li>
 *   <li>Strip {@code ctx.pushSteering(...)} on brake → steering queue empty → test RED</li>
 *   <li>Strip {@code ctx.requestForceFinish(...)} on maxStagnations → loop spins forever → test RED</li>
 * </ul>
 *
 * @since 2026-07
 */
public class StagnationDetectionRail extends AgentRail {
    /**
     * Max identical final-answer outputs before brake steering.
     */
    public static final int MAX_OUTPUT_REPEATS = 3;

    /**
     * Max identical tool-call sequences before brake steering.
     */
    public static final int MAX_TOOL_CYCLE_REPEATS = 3;

    /**
     * Total stagnation events before forceFinish(degraded).
     */
    public static final int MAX_STAGNATIONS = 2;

    /**
     * How many recent output hashes to keep in the sliding window.
     */
    public static final int OUTPUT_HISTORY_SIZE = 5;

    /**
     * How many recent tool-call signatures to keep.
     */
    public static final int TOOL_HISTORY_SIZE = 8;

    // Result keys (aligned with bridge rail naming)
    /** Result key for degraded terminal state. */
    public static final String DEGRADED_KEY = "degraded";

    /** Result key indicating stagnation detection. */
    public static final String STAGNATION_KEY = "stagnation_detected";

    /** Result key for stagnation reason. */
    public static final String STAGNATION_REASON_KEY = "stagnation_reason";

    /** Result key for output text. */
    public static final String OUTPUT_KEY = "output";

    /**
     * Priority: 50 — medium, between voting critic (100) and bridge rail (0).
     */
    private static final int PRIORITY = 50;

    private final PromptInjectionState injectionState;
    private final String stateKey = RailInvocationState.newKey(StagnationDetectionRail.class);

    /**
     * Creates a detector connected to the target model's prompt state.
     *
     * @param injectionState target model's prompt injection state
     */
    public StagnationDetectionRail(PromptInjectionState injectionState) {
        this.injectionState = Objects.requireNonNull(injectionState, "injectionState");
        setPriority(PRIORITY);
    }

    // ---- Test observation points ----

    /**
     * Current consecutive output repeat count.
     *
     * @param ctx current invocation callback context
     * @return current consecutive output repeat count
     */
    public int getConsecutiveOutputRepeats(AgentCallbackContext ctx) {
        return state(ctx).consecutiveOutputRepeats;
    }

    /**
     * Total stagnation events.
     *
     * @param ctx current invocation callback context
     * @return total number of stagnation events
     */
    public int getTotalStagnations(AgentCallbackContext ctx) {
        return state(ctx).totalStagnations;
    }

    /**
     * Output history window (copy).
     *
     * @param ctx current invocation callback context
     * @return copied output history window
     */
    public List<String> getOutputHistory(AgentCallbackContext ctx) {
        return List.copyOf(state(ctx).outputHistory);
    }

    /**
     * Current tool cycle repeat count.
     *
     * @param ctx current invocation callback context
     * @return current tool cycle repeat count
     */
    public int getToolCycleRepeats(AgentCallbackContext ctx) {
        return state(ctx).toolCycleRepeats;
    }

    // ============================================================
    // Hook: afterModelCall
    // ============================================================

    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }

        InvocationState state = state(ctx);
        if (isFinalAnswer(msg)) {
            checkOutputStagnation(ctx, state, msg);
        } else {
            checkToolCycleStagnation(ctx, state, msg);
        }
    }

    // ============================================================
    // Hook: onToolException
    // ============================================================

    /**
     * Track consecutive tool failures for stagnation.
     * If the same tool fails {@code >= 3} times, it's a stagnation pattern.
     *
     * @param context callback context carrying tool-call failure inputs
     */
    @Override
    public void onToolException(AgentCallbackContext context) {
        if (context.getInputs() instanceof ToolCallInputs tci) {
            InvocationState state = state(context);
            String toolName = tci.getToolName();
            if (toolName != null && toolName.equals(state.lastFailedTool)) {
                state.consecutiveToolFailures++;
                if (state.consecutiveToolFailures >= 3) {
                    String failureTool = state.lastFailedTool;
                    injectionState
                            .setPhaseOverride("Tool " + failureTool + " has failed " + state.consecutiveToolFailures
                                    + " consecutive times. Do NOT retry this tool — use a different"
                                    + " tool or change your strategy.");
                    state.consecutiveToolFailures = 0; // Reset after signal
                }
            } else {
                state.lastFailedTool = toolName;
                state.consecutiveToolFailures = 1;
            }
        }
    }

    // ============================================================
    // Output stagnation detection
    // ============================================================

    private void checkOutputStagnation(AgentCallbackContext ctx, InvocationState state, AssistantMessage msg) {
        String output = contentOf(msg);
        if (output == null || output.isEmpty()) {
            return;
        }

        // Build a lightweight content hash (first 200 chars for comparison)
        String hash = output.length() > 200 ? output.substring(0, 200) : output;

        boolean isRepeat = state.outputHistory.contains(hash);

        if (isRepeat) {
            state.consecutiveOutputRepeats++;
        } else {
            state.consecutiveOutputRepeats = 0;
        }

        // Add to sliding window
        state.outputHistory.add(hash);
        while (state.outputHistory.size() > OUTPUT_HISTORY_SIZE) {
            state.outputHistory.remove(0);
        }

        // Trigger brake when consecutive repeats reach threshold
        if (state.consecutiveOutputRepeats >= MAX_OUTPUT_REPEATS) {
            String brake = "【检测到输出重复】您的回答与之前的回答高度相似（第" + state.consecutiveOutputRepeats + "次重复）。请提供全新的分析，使用不同的论据和方法。";

            ctx.pushSteering(brake);
            RailTelemetry.current().fire(new RailEvent.SteeringEvent("StagnationDetectionRail",
                    "STAGNATION_OUTPUT", brake.substring(0, Math.min(80, brake.length()))));
            injectionState.setPhaseOverride(
                    "BREAK_STAGNATION: You are producing repetitive output." + " Change your approach entirely.");

            state.totalStagnations++;
            state.consecutiveOutputRepeats = 0; // Reset count after brake

            if (state.totalStagnations >= MAX_STAGNATIONS) {
                ctx.requestForceFinish(degradedResult("输出重复已达" + state.totalStagnations + "次，强制降级终态"));
            }
        }
    }

    // ============================================================
    // Tool cycle detection
    // ============================================================

    private void checkToolCycleStagnation(AgentCallbackContext ctx, InvocationState state, AssistantMessage msg) {
        String signature = buildToolSignature(msg);
        if (signature == null || signature.isEmpty()) {
            return;
        }

        state.toolSignatureHistory.add(signature);
        while (state.toolSignatureHistory.size() > TOOL_HISTORY_SIZE) {
            state.toolSignatureHistory.remove(0);
        }

        // Check if the last N signatures form a cycle.
        // Only increment on even-sized histories (odd sizes don't divide evenly).
        // Do NOT reset on non-match — odd sizes are a structural artifact, not a
        // signal that the cycle was broken.
        if (detectToolCycle(state)) {
            state.toolCycleRepeats++;
        }

        state.lastToolRoundSignature = signature;

        // Trigger brake when cycle repeats
        if (state.toolCycleRepeats >= MAX_TOOL_CYCLE_REPEATS) {
            String brake = "【检测到工具调用循环】您正在重复使用相同的工具序列（第" + state.toolCycleRepeats + "次）。请尝试完全不同的工具或方法，不要重复已证明无效的调用路径。";

            ctx.pushSteering(brake);
            RailTelemetry.current().fire(new RailEvent.SteeringEvent("StagnationDetectionRail",
                    "STAGNATION_TOOLCYCLE", brake.substring(0, Math.min(80, brake.length()))));
            injectionState.setPhaseOverride("BREAK_LOOP: You are repeating the same tool-call sequence."
                    + " This loop is ineffective. Change strategy now.");

            state.totalStagnations++;
            state.toolCycleRepeats = 0; // Reset after brake

            if (state.totalStagnations >= MAX_STAGNATIONS) {
                ctx.requestForceFinish(degradedResult("工具调用循环已达" + state.totalStagnations + "次，强制降级终态"));
            }
        }
    }

    /**
     * Build a signature string from the tool calls in this response.
     * Format: "toolName1|toolName2|..." (order-preserved for sequence matching).
     *
     * @param msg assistant message whose tool calls form the signature
     * @return pipe-separated tool signature, or empty string when there are no tool calls
     */
    private static String buildToolSignature(AssistantMessage msg) {
        if (msg.getToolCalls() == null || msg.getToolCalls().isEmpty()) {
            return "";
        }
        return msg.getToolCalls().stream().map(ToolCall::getName).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.joining("|"));
    }

    /**
     * Simple cycle detection: check if the last half of the history
     * repeats the half before it. Only fires on even-sized histories
     * so the two halves have equal length (odd size → halves differ).
     *
     * @param state current invocation state
     * @return true when the recent tool signatures form a repeated cycle
     */
    private static boolean detectToolCycle(InvocationState state) {
        int sz = state.toolSignatureHistory.size();
        if (sz < 4 || (sz & 1) != 0) {
            return false; // need even size for equal halves
        }
        int half = sz / 2;
        List<String> firstHalf = state.toolSignatureHistory.subList(0, half);
        List<String> secondHalf = state.toolSignatureHistory.subList(half, sz);
        return firstHalf.equals(secondHalf);
    }

    // ============================================================
    // Helpers
    // ============================================================

    private static boolean isFinalAnswer(AssistantMessage msg) {
        return msg.getToolCalls() == null || msg.getToolCalls().isEmpty();
    }

    private static String contentOf(AssistantMessage msg) {
        String c = msg.getContentAsString();
        return c != null ? c : "";
    }

    private InvocationState state(AgentCallbackContext ctx) {
        return RailInvocationState.get(ctx, stateKey, InvocationState.class, InvocationState::new);
    }

    private static Map<String, Object> degradedResult(String reason) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ObservingRail.SOURCE_RAIL_KEY, "StagnationDetectionRail");
        result.put(DEGRADED_KEY, true);
        result.put(STAGNATION_KEY, true);
        result.put(STAGNATION_REASON_KEY, reason);
        result.put(OUTPUT_KEY, reason);
        return result;
    }

    private static final class InvocationState {
        private final List<String> outputHistory = new ArrayList<>();
        private final List<String> toolSignatureHistory = new ArrayList<>();
        private int consecutiveOutputRepeats;
        private int toolCycleRepeats;
        private int totalStagnations;
        private String lastToolRoundSignature;
        private String lastFailedTool;
        private int consecutiveToolFailures;
    }
}
