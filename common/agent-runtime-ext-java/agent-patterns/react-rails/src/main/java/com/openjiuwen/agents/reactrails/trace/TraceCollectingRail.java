/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.trace;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

/**
 * Cognitive rail that collects invocation-level trace data for post-hoc feedback
 * analysis (E-hill-climbing-trace-feedback pattern).
 *
 * <p>Hooks two lifecycle points:
 * <ul>
 *   <li>{@code afterModelCall} — counts model invocations, records tool-call names
 *       vs final-answer content. No dependency on other rails' forceFinish state
 *       (reads only what it observes directly).</li>
 *   <li>{@code onToolException} — records the failing tool name for device-failure
 *       correlation in the trace analyzer.</li>
 * </ul>
 *
 * <p>No {@code afterInvoke} hook: cleanup is done by the tuner via {@link #endTrace()}.
 * The {@code afterInvoke} hook fires INSIDE {@code agent.invoke()} before control
 * returns to the caller — clearing the ThreadLocal there would make
 * {@link #endTrace()} return null after invoke finishes.
 *
 * <p><b>SDK boundary</b>: extends {@link AgentRail} from agent-core-java 0.1.12 jar
 * (unchanged). Uses only public API methods verified by bytecode.
 *
 * <p><b>IFF contract</b>: strip {@code incrementModelCallCount} in afterModelCall →
 * trace records 0 modelCalls → RED in tests.
 * Strip {@code addToolCall} → toolCalls always empty → RED.
 */
public class TraceCollectingRail extends AgentRail {

    /**
     * ThreadLocal isolates traces across concurrent invocations on the same rail
     * instance (possible when the same agent is called from multiple threads).
     */
    private final ThreadLocal<TraceFeedbackRecord> currentTrace = new ThreadLocal<>();

    // ================================================================
    // Rail hooks
    // ================================================================

    /**
     * After each model call: increment counter, record tool calls or final answer length.
     *
     * <p>Intentionally does NOT read {@code ctx.hasForceFinishRequest()} or
     * {@code ctx.getForceFinishRequest()} — the verify outcome is read post-invoke
     * from the agent's return value to avoid priority-ordering assumptions vs
     * {@code CriteriaReplanBridgeRail} / {@code PevReplanRail}.
     */
    @Override
    public synchronized void afterModelCall(AgentCallbackContext ctx) {
        TraceFeedbackRecord trace = currentTrace.get();
        if (trace == null) {
            return;
        }

        trace.incrementModelCallCount();

        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
            return;
        }
        if (!(inputs.getResponse() instanceof AssistantMessage msg)) {
            return;
        }

        // Record tool calls if this round made them; otherwise it's a final answer
        if (msg.getToolCalls() != null && !msg.getToolCalls().isEmpty()) {
            for (ToolCall tc : msg.getToolCalls()) {
                trace.addToolCall(tc.getName());
            }
        } else {
            // Final answer — record output length as a quality proxy
            String content = msg.getContentAsString();
            trace.setFinalOutput(content != null ? content : "");
        }
    }

    /**
     * Records tool failures (device-failure signal) for the trace analyzer.
     *
     * <p>Complements RootCauseRail's onToolException which defers the degrade
     * decision — this rail only records the event, never modifies agent flow.
     */
    @Override
    public synchronized void onToolException(AgentCallbackContext ctx) {
        TraceFeedbackRecord trace = currentTrace.get();
        if (trace == null) {
            return;
        }

        String toolName = "__unknown__";
        if (ctx.getInputs() instanceof ToolCallInputs tci && tci.getToolName() != null) {
            toolName = tci.getToolName();
        }
        trace.addToolException(toolName);
    }

    // ================================================================
    // External lifecycle (called by HillClimbingTuner, not from rail hooks)
    // ================================================================

    /**
     * Start a new trace for the upcoming {@code agent.invoke()} call.
     * Must be called BEFORE the invoke.
     */
    public void beginTrace() {
        currentTrace.set(new TraceFeedbackRecord());
    }

    /**
     * End the current trace and return the collected record.
     * Must be called AFTER the invoke completes.
     *
     * @return the completed trace record, or null if beginTrace was not called
     */
    public TraceFeedbackRecord endTrace() {
        TraceFeedbackRecord record = currentTrace.get();
        currentTrace.remove();
        return record;
    }
}
