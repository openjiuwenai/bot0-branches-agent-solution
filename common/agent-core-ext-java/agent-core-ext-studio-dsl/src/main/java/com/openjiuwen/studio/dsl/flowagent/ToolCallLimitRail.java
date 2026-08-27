/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowagent;

import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Limit ReAct by tool-call rounds instead of total model iterations.
 *
 * <p>Parity with Python {@code tool_call_limit_rail.ToolCallLimitRail}.
 *
 * @since 2026-08-27
 */

public final class ToolCallLimitRail extends AgentRail {
    static final String COUNT_KEY = "flow_agent_tool_call_rounds";

    private final int maxToolCallRounds;

    /**
     * ToolCallLimitRail.
     * @param maxToolCallRounds maxToolCallRounds
     * @since 0.1.0
     */
    public ToolCallLimitRail(int maxToolCallRounds) {
        this.maxToolCallRounds = maxToolCallRounds;
    }

    /**
     * afterModelCall.
     *
     * @param ctx ctx
     * @since 0.1.0
     */

    @Override
    public void afterModelCall(AgentCallbackContext ctx) {
        if (!(ctx.getInputs() instanceof ModelCallInputs inputs)) {
        return;
    }
        Object response = inputs.getResponse();
        if (!(response instanceof AssistantMessage assistant)) {
            return;
        }
        List<ToolCall> toolCalls = assistant.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }

        Map<String, Object> extra = ctx.getExtra() != null ? ctx.getExtra() : new LinkedHashMap<>();
        int completedRounds = extra.get(COUNT_KEY) instanceof Number n ? n.intValue() : 0;
        if (completedRounds >= maxToolCallRounds) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("output", "Max iterations reached without completion");
            result.put("result_type", "error");
            ctx.requestForceFinish(result);
            return;
        }

        extra.put(COUNT_KEY, completedRounds + 1);
        ctx.setExtra(extra);
    }
}
