/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.deepb;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.harness.rails.interrupt.BaseInterruptRail;
import com.openjiuwen.harness.rails.interrupt.InterruptDecision;

import java.util.List;

/**
 * Demo ask-user rail that feeds resume input back into {@code AskUserTool}.
 *
 * @since 2026-07-03
 */
class DemoAskUserRail extends BaseInterruptRail {
    DemoAskUserRail() {
        super(List.of("ask_user"));
    }

    @Override
    protected InterruptDecision resolveInterrupt(AgentCallbackContext ctx, ToolCall toolCall, Object userInput) {
        if (userInput != null) {
            return approve("{\"response\":\"" + escapeJson(String.valueOf(userInput)) + "\"}");
        }
        return interrupt(InterruptRequest.builder()
                .message("ask_user")
                .build());
    }

    private String escapeJson(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }
}
