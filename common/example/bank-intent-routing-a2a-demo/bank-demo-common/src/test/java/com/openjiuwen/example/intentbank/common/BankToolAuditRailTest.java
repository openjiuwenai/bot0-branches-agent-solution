/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import org.junit.jupiter.api.Test;

/** Tests bank Tool call and result audit logging. */
class BankToolAuditRailTest {
    @Test
    void observesToolCallWithoutChangingExecutionInput() {
        ToolCall toolCall = ToolCall.builder().id("call-1").name("intent_match").arguments("{\"semantic\":\"查询余额\"}")
                .build();
        ToolCallInputs inputs = ToolCallInputs.builder().toolCall(toolCall).toolName(toolCall.getName())
                .toolArgs(toolCall.getArguments()).toolResult("{\"status\":\"MATCHED\"}").build();

        BankToolAuditRail rail = new BankToolAuditRail();
        AgentCallbackContext context = AgentCallbackContext.builder().inputs(inputs).build();
        rail.beforeToolCall(context);
        rail.afterToolCall(context);

        assertThat(rail.getPriority()).isEqualTo(BankToolAuditRail.PRIORITY);
        assertThat(inputs.getToolName()).isEqualTo("intent_match");
        assertThat(inputs.getToolArgs()).isEqualTo("{\"semantic\":\"查询余额\"}");
        assertThat(inputs.getToolResult()).isEqualTo("{\"status\":\"MATCHED\"}");
        assertThat(toolCall.getName()).isEqualTo("intent_match");
    }
}
