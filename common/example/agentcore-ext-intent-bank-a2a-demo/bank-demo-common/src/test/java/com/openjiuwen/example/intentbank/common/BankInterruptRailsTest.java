/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.intentbank.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.example.intentbank.common.BankInterruptRails.ConfirmationRail;
import com.openjiuwen.example.intentbank.common.BankInterruptRails.IntentChangeTerminationRail;
import com.openjiuwen.example.intentbank.common.BankInterruptRails.IntentAwareAskUserRail;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/** Tests bank follow-up, confirmation, and intent-change Rails. */
class BankInterruptRailsTest {
    @Test
    void askUserInterruptsAndFeedsOrdinaryResumeBackToTool() {
        IntentAwareAskUserRail rail = new IntentAwareAskUserRail();
        AgentCallbackContext initial = context("ask_user", "{\"query\":\"收款人是谁？\"}", null);

        assertThatThrownBy(() -> rail.beforeToolCall(initial)).isInstanceOfSatisfying(ToolInterruptException.class,
                exception -> assertThat(exception.getRequest().getMessage()).isEqualTo("收款人是谁？"));

        AgentCallbackContext resumed = context("ask_user", "{\"query\":\"收款人是谁？\"}", "张三");
        rail.beforeToolCall(resumed);
        assertThat(String.valueOf(toolInputs(resumed).getToolArgs())).contains("\"query\":\"收款人是谁？\"",
                "\"response\":\"张三\"");
        assertThat(resumed.getExtra()).doesNotContainKey("_skip_tool");
    }

    @Test
    void intentChangeTerminatesBusinessAgentWithStructuredResult() {
        AgentCallbackContext context = context(BankTools.TRANSFER, "{}", null);
        toolInputs(context).setToolResult(Map.of("status", "INTENT_CHANGED", "latestSemantic", "购买1000元理财"));

        new IntentChangeTerminationRail().afterToolCall(context);

        assertThat(context.hasForceFinishRequest()).isTrue();
        assertThat(context.getForceFinishRequest().getResult()).containsEntry("status", "INTENT_CHANGED")
                .containsEntry("latestSemantic", "购买1000元理财");
    }

    @Test
    void returnsIntentChangedInsteadOfContinuingInterruptedBusiness() {
        IntentAwareAskUserRail rail = new IntentAwareAskUserRail();
        AgentCallbackContext resumed = context("ask_user", "{\"query\":\"转给谁？\"}", "我要购买稳盈90天理财");

        rail.beforeToolCall(resumed);

        assertThat(resumed.getExtra()).containsEntry("_skip_tool", true);
        assertThat(result(resumed)).containsEntry("status", "INTENT_CHANGED").containsEntry("latestSemantic",
                "我要购买稳盈90天理财");
    }

    @Test
    void confirmationSupportsInterruptApproveCancelAndIntentChange() {
        ConfirmationRail rail = new ConfirmationRail(BankTools.TRANSFER, "请确认转账");
        AgentCallbackContext initial = context(BankTools.TRANSFER, "{\"recipient\":\"张三\",\"amount\":100}", null);
        assertThatThrownBy(() -> rail.beforeToolCall(initial)).isInstanceOfSatisfying(ToolInterruptException.class,
                exception -> assertThat(exception.getRequest().getMessage()).isEqualTo("请确认转账"));

        AgentCallbackContext approved = context(BankTools.TRANSFER, "{\"recipient\":\"张三\",\"amount\":100}", "确认");
        rail.beforeToolCall(approved);
        assertThat(approved.getExtra()).doesNotContainKey("_skip_tool");

        AgentCallbackContext cancelled = context(BankTools.TRANSFER, "{\"recipient\":\"张三\",\"amount\":100}", "取消");
        rail.beforeToolCall(cancelled);
        assertThat(result(cancelled)).containsEntry("status", "CANCELLED");

        AgentCallbackContext changed = context(BankTools.TRANSFER, "{\"recipient\":\"张三\",\"amount\":100}", "改为查询余额");
        rail.beforeToolCall(changed);
        assertThat(result(changed)).containsEntry("status", "INTENT_CHANGED");
    }

    private static AgentCallbackContext context(String toolName, String arguments, Object resumeInput) {
        ToolCall call = ToolCall.builder().id("call-1").name(toolName).arguments(arguments).build();
        Map<String, Object> extra = new HashMap<>();
        if (resumeInput != null) {
            extra.put("_resume_user_input", resumeInput);
        }
        return AgentCallbackContext.builder()
                .inputs(ToolCallInputs.builder().toolCall(call).toolName(toolName).toolArgs(arguments).build())
                .extra(extra).build();
    }

    private static Map<String, Object> result(AgentCallbackContext context) {
        Object result = toolInputs(context).getToolResult();
        if (!(result instanceof Map<?, ?> map)) {
            throw new AssertionError("expected a map Tool result");
        }
        Map<String, Object> copy = new HashMap<>();
        map.forEach((key, value) -> copy.put(String.valueOf(key), value));
        return copy;
    }

    private static ToolCallInputs toolInputs(AgentCallbackContext context) {
        if (context.getInputs() instanceof ToolCallInputs inputs) {
            return inputs;
        }
        throw new AssertionError("expected ToolCallInputs");
    }
}
