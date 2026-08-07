/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Tests conversion of intent actions to Runtime A2A interruptions. */
class A2ADelegateRailTest {
    @Test
    void interruptsRegisteredTargetWithoutExposingToolCard() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("transfer-agent", card());
        A2ADelegateRail rail = new A2ADelegateRail(registry);
        AgentCallbackContext context = context("call-1",
                "{\"agentName\":\"transfer-agent\",\"remoteInput\":\"transfer 100 to Zhang San\"}", Map.of());

        assertThatThrownBy(() -> rail.beforeToolCall(context)).isInstanceOfSatisfying(ToolInterruptException.class,
                exception -> {
                    assertThat(exception.getRequest().getMessage()).isEqualTo("transfer 100 to Zhang San");
                    assertThat(exception.getRequest().getContext()).containsEntry("agentName", "transfer-agent")
                            .containsEntry("_interrupt_kind", "a2a_delegate");
                    assertThat(exception.getToolCall().getId()).isEqualTo("call-1");
                });
        assertThat(rail.getTools()).isEmpty();
        assertThat(rail.getPriority()).isLessThan(110);
    }

    @Test
    void returnsClearToolResultForMissingTargetOrInvalidArguments() {
        A2ADelegateRail rail = new A2ADelegateRail(new A2ARemoteAgentCardRegistry());
        AgentCallbackContext missing = context("call-2", "{\"agentName\":\"missing\",\"remoteInput\":\"hello\"}",
                Map.of());
        rail.beforeToolCall(missing);
        ToolCallInputs missingInputs = toolInputs(missing);
        assertThat(missing.getExtra()).containsEntry("_skip_tool", true);
        assertThat(missingInputs.getToolResult()).isEqualTo("Remote A2A agent is not registered: missing");

        AgentCallbackContext invalid = context("call-3", "{}", Map.of());
        rail.beforeToolCall(invalid);
        assertThat(toolInputs(invalid).getToolResult())
                .isEqualTo("a2a_delegate requires non-blank agentName and remoteInput");
    }

    @Test
    void resumeUsesRemoteResultAndDoesNotInterruptAgain() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        registry.register("transfer-agent", card());
        A2ADelegateRail rail = new A2ADelegateRail(registry);
        AgentCallbackContext resumed = context("call-4",
                "{\"agentName\":\"transfer-agent\",\"remoteInput\":\"transfer\"}",
                Map.of("_resume_user_input", "transfer completed"));

        rail.beforeToolCall(resumed);

        ToolCallInputs inputs = toolInputs(resumed);
        assertThat(resumed.getExtra()).containsEntry("_skip_tool", true);
        assertThat(inputs.getToolResult()).isEqualTo("transfer completed");
        assertThat(inputs.getToolMsg().getToolCallId()).isEqualTo("call-4");
    }

    private static AgentCallbackContext context(String id, String arguments, Map<String, Object> extra) {
        ToolCall toolCall = ToolCall.builder().id(id).name(A2ADelegateRail.TARGET_NAME).arguments(arguments).build();
        return AgentCallbackContext.builder().inputs(ToolCallInputs.builder().toolCall(toolCall)
                .toolName(A2ADelegateRail.TARGET_NAME).toolArgs(arguments).build()).extra(new HashMap<>(extra)).build();
    }

    private static ToolCallInputs toolInputs(AgentCallbackContext context) {
        if (context.getInputs() instanceof ToolCallInputs inputs) {
            return inputs;
        }
        throw new AssertionError("expected ToolCallInputs");
    }

    private static AgentCard card() {
        AgentSkill skill = new AgentSkill("transfer", "Transfer", "Transfer funds", List.of(), List.of(),
                List.of("text/plain"), List.of("text/plain"), List.of());
        return AgentCard.builder().name("transfer").description("transfer").version("1.0")
                .capabilities(new AgentCapabilities(false, false, false, null)).defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "http://localhost/a2a", null, "1.0")))
                .skills(List.of(skill)).build();
    }
}
