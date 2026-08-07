/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Records model-selected Tool order for the executable demo acceptance scripts. */
public final class BankToolAuditRail extends AgentRail {
    /** Runs before the intent routing Rail rewrites the model-selected ToolCall. */
    public static final int PRIORITY = 120;

    private static final Logger log = LoggerFactory.getLogger(BankToolAuditRail.class);

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    @Override
    public void beforeToolCall(AgentCallbackContext context) {
        if (!(context.getInputs() instanceof ToolCallInputs inputs)) {
            return;
        }
        ToolCall toolCall = inputs.getToolCall();
        String toolName = inputs.getToolName();
        if ((toolName == null || toolName.isBlank()) && toolCall != null) {
            toolName = toolCall.getName();
        }
        Object arguments = inputs.getToolArgs();
        if (arguments == null && toolCall != null) {
            arguments = toolCall.getArguments();
        }
        log.info("BANK_DEMO_TOOL_CALL tool={} arguments={}", toolName, arguments);
    }

    @Override
    public void afterToolCall(AgentCallbackContext context) {
        if (!(context.getInputs() instanceof ToolCallInputs inputs) || inputs.getToolResult() == null) {
            return;
        }
        log.info("BANK_DEMO_TOOL_RESULT tool={} result={}", inputs.getToolName(), inputs.getToolResult());
    }
}
