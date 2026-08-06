/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.selfheal;

import com.openjiuwen.agents.reactrails.observability.ObservingRail;
import com.openjiuwen.agents.reactrails.observability.RailEvent;
import com.openjiuwen.agents.reactrails.observability.RailTelemetry;
import com.openjiuwen.agents.reactrails.state.RailInvocationState;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Beta cognitive rail for ReActAgent — device-failure self-heal gate.
 *
 * <p>Tool failures (exceptions during tool execution) signal device breakdown — retrying
 * the same input re-raises the same error. This rail detects tool failures and degrades
 * honestly instead of letting ReActAgent spin through maxIterations on a broken tool.
 *
 * <p><b>Dual-hook collaboration</b> (bytecode-verified consumption at offsets 700 & 878).
 * ReActAgent.invoke has multiple consumeForceFinish sites on the 0.1.13 jar, all reading the same
 * shared AgentCallbackContext.forceFinishRequest field (context built once at invoke-entry, astore 8):
 * <ul>
 *   <li>offset 225: BEFORE_INVOKE → before the loop (not used here).</li>
 *   <li>offset 700: after callModel/afterModelCall, BEFORE executeToolCallEntries → consumes
 *       forceFinish set in afterModelCall.</li>
 *   <li>offset 878: AFTER executeToolCallEntries → consumes forceFinish set inside tool execution.
 *       AbilityManager.railedExecuteSingleToolCall fires ON_TOOL_EXCEPTION via RailExecutor on
 *       tool throw, so onToolException runs WITHIN executeToolCallEntries.</li>
 * </ul>
 * Both hooks CAN forceFinish. This rail deliberately splits the work across two hooks even though
 * a single-hook design (requestForceFinish directly in onToolException, consumed at offset 878
 * same-iteration) would also terminate the loop:
 * <ul>
 *   <li>{@code onToolException}: tool failure signal → record failed tool name, mark
 *       pending degrade. Defers termination on purpose so the loop can still surface any
 *       remaining tool messages from the same batch before degrading.</li>
 *   <li>{@code afterModelCall} (next iteration, offset 700): if pending degrade exists, fire
 *       {@code requestForceFinish(degraded)} — consumed at offset 700, short-circuits before
 *       the next tool batch runs.</li>
 * </ul>
 * The deferral is a conservative choice, not a structural necessity on this jar version.
 *
 * <p>Honest boundary: this rail covers DeviceFailure → Degrade only.
 * PerceptionUnreliable / PlanOrAnswerError need criteria-verify signal integration
 * ({@link com.openjiuwen.agents.reactrails.verification.CriteriaVerificationRail}), deferred.
 *
 * @since 2026-07
 */
public class RootCauseRail extends AgentRail {
    /** Result key indicating root-cause degrade. */
    public static final String ROOT_CAUSE_DEGRADED_KEY = "root_cause_degraded";

    /** Result key for degraded terminal state. */
    public static final String DEGRADED_KEY = "degraded";

    /** Result key for root cause category. */
    public static final String ROOT_CAUSE_KEY = "root_cause";

    /** Result key for degrade reason. */
    public static final String REASON_KEY = "reason";

    private final String stateKey = RailInvocationState.newKey(RootCauseRail.class);

    /**
     * Test observation: is there a pending degrade (onToolException marked, afterModelCall not yet fired)?
     *
     * @param context current invocation callback context
     * @return true when a tool failure is waiting to be degraded
     */
    public boolean hasPendingDegrade(AgentCallbackContext context) {
        return state(context).hasPendingDegrade;
    }

    /**
     * 工具异常钩子：设备故障信号→记录失败工具名并标记待降级（不在本钩子终止）。
     *
     * @param context callback context carrying tool-call failure inputs
     */
    @Override
    public void onToolException(AgentCallbackContext context) {
        // Tool failure → device breakdown → mark for degrade (afterModelCall will terminate)
        InvocationState state = state(context);
        state.failedTool = extractToolName(context);
        state.hasPendingDegrade = true;
        RailTelemetry.current().fire(new RailEvent.DeviceFailureEvent("RootCauseRail", state.failedTool, "MARKED"));
    }

    /**
     * 模型回调钩子：若存在待降级标记，触发 forceFinish 降级终态并清除标记。
     *
     * @param context callback context used to request forced finish
     */
    @Override
    public void afterModelCall(AgentCallbackContext context) {
        InvocationState state = state(context);
        if (state.hasPendingDegrade) {
            Map<String, Object> d = degradedMap(state.failedTool);
            context.requestForceFinish(d);
            state.hasPendingDegrade = false;
            RailTelemetry.current().fire(new RailEvent.DeviceFailureEvent("RootCauseRail", state.failedTool, "FIRED"));
        }
    }

    private InvocationState state(AgentCallbackContext context) {
        return RailInvocationState.get(context, stateKey, InvocationState.class, InvocationState::new);
    }

    private static String extractToolName(AgentCallbackContext context) {
        if (context.getInputs() instanceof ToolCallInputs inputs && inputs.getToolName() != null) {
            return inputs.getToolName();
        }
        return "__unknown_tool__";
    }

    private static Map<String, Object> degradedMap(String tool) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(ObservingRail.SOURCE_RAIL_KEY, "RootCauseRail");
        result.put(ROOT_CAUSE_DEGRADED_KEY, true);
        result.put(DEGRADED_KEY, true);
        result.put(ROOT_CAUSE_KEY, "DeviceFailure");
        result.put(REASON_KEY, "工具失败: " + tool + " — 设备故障，重试无效，降级终态");
        return result;
    }

    private static final class InvocationState {
        private boolean hasPendingDegrade;
        private String failedTool;
    }
}
