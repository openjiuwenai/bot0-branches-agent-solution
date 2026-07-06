/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.agentcoreext.deepb;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.rails.interrupt.AskUserTool;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies Agent B uses DeepAgent-native interrupt components.
 *
 * @since 2026-07-03
 */
class AgentBDeepAgentApplicationTest {
    @Test
    void buildsDeepAgentWithNativeAskUserInterrupt() {
        DeepAgent agent = AgentBDeepAgentApplication.buildDeepAgent(configuredProperties());

        assertThat(agent.getConfig().getRails()).hasAtLeastOneElementOfType(DemoAskUserRail.class);
        assertThat(agent.getConfig().getTools()).hasAtLeastOneElementOfType(AskUserTool.class);
        assertThat(agent.getConfig().isEnableTaskLoop()).isTrue();
    }

    @Test
    void agentBUsesOpenAiCompatibleDeepSeekConfiguration() {
        DeepAgentLlmProperties properties = configuredProperties();

        Map<String, Object> backend = properties.backendConfig();
        Map<String, Object> model = properties.modelConfig();

        assertThat(backend)
                .containsEntry("provider", "OpenAI")
                .containsEntry("api_key", "test-key")
                .containsEntry("api_base", "https://api.deepseek.com");
        assertThat(model).containsEntry("model", "deepseek-chat");
        assertThat(properties.getSystemPrompt()).contains("ask_user").contains("round-3-confirmed");
    }

    @Test
    void askUserRailInterruptsWhenResumeInputIsMissing() {
        DemoAskUserRail rail = new DemoAskUserRail();
        AgentCallbackContext ctx = askUserContext(null);

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOf(ToolInterruptException.class)
                .hasMessage("ask_user");
    }

    @Test
    void askUserRailApprovesWithResumeInputAsToolResponse() {
        DemoAskUserRail rail = new DemoAskUserRail();
        AgentCallbackContext ctx = askUserContext("round-3-confirmed: transfer confirmation has been provided");

        rail.beforeToolCall(ctx);

        assertThat(ctx.getInputs()).isInstanceOfSatisfying(ToolCallInputs.class, inputs ->
                assertThat(inputs.getToolArgs()).isEqualTo(
                        "{\"response\":\"round-3-confirmed: transfer confirmation has been provided\"}"));
    }

    private AgentCallbackContext askUserContext(String resumeInput) {
        ToolCall toolCall = ToolCall.builder()
                .id("agent-b-round-2")
                .name("ask_user")
                .arguments("{\"response\":\"question\"}")
                .build();
        LinkedHashMap<String, Object> extra = new LinkedHashMap<>();
        if (resumeInput != null) {
            extra.put(ToolInterruptionState.RESUME_USER_INPUT_KEY, resumeInput);
        }
        return AgentCallbackContext.builder()
                .inputs(ToolCallInputs.builder()
                        .toolCall(toolCall)
                        .toolName("ask_user")
                        .toolArgs(toolCall.getArguments())
                        .build())
                .extra(extra)
                .build();
    }

    private static DeepAgentLlmProperties configuredProperties() {
        DeepAgentLlmProperties properties = new DeepAgentLlmProperties();
        properties.setApiKey("test-key");
        properties.setTimeout(Duration.ofSeconds(10));
        return properties;
    }
}
