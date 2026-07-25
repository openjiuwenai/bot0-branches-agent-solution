/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.foundation.tool.schema.ToolInfo;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.singleagent.BaseAgent;
import com.openjiuwen.core.singleagent.ReActAgent;
import com.openjiuwen.core.singleagent.interrupt.ToolCallInterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentCallbackEvent;
import com.openjiuwen.core.singleagent.rail.EventInputs;
import com.openjiuwen.core.singleagent.rail.ModelCallInputs;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests request-scoped client tool registration, interruption, and resumption.
 *
 * @since 2026-07-25
 */
class ClientToolRailTest {
    @Test
    void injectsVisibleToolsForExactSessionAndUnregistersOnClose() {
        ReActAgent agent = agent();
        ModelCallInputs matchingInputs = ModelCallInputs.builder().tools(new ArrayList<>()).build();
        ModelCallInputs otherInputs = ModelCallInputs.builder().tools(new ArrayList<>()).build();

        try (ClientToolRail.Binding ignored = bind(agent, "ctx", List.of(clientTool("readCurrentPage")), null)) {
            execute(agent, AgentCallbackEvent.BEFORE_MODEL_CALL, context(matchingInputs, "ctx"));
            execute(agent, AgentCallbackEvent.BEFORE_MODEL_CALL, context(otherInputs, "ctx-other"));

            assertThat(matchingInputs.getTools()).extracting(ToolInfo::getName).containsExactly("readCurrentPage");
            assertThat(otherInputs.getTools()).isEmpty();
            assertThat(agent.getAgentCallbackManager().hasHooks(AgentCallbackEvent.BEFORE_MODEL_CALL)).isTrue();
        }

        assertThat(agent.getAgentCallbackManager().hasHooks(AgentCallbackEvent.BEFORE_MODEL_CALL)).isFalse();
        assertThat(agent.getAgentCallbackManager().hasHooks(AgentCallbackEvent.BEFORE_TOOL_CALL)).isFalse();
    }

    @Test
    void deepAgentBindingRequiresCompleteNumericSessionSuffix() {
        DeepAgent deepAgent = new DeepAgent(agentCard(), DeepAgentConfig.builder().enableTaskLoop(true).build(), null);
        ModelCallInputs derived = ModelCallInputs.builder().tools(new ArrayList<>()).build();
        ModelCallInputs raw = ModelCallInputs.builder().tools(new ArrayList<>()).build();
        ModelCallInputs prefixCollision = ModelCallInputs.builder().tools(new ArrayList<>()).build();

        try (ClientToolRail.Binding ignored = bind(deepAgent, "ctx", List.of(clientTool("readCurrentPage")), null)) {
            BaseAgent innerAgent = deepAgent.getAgent();
            execute(innerAgent, AgentCallbackEvent.BEFORE_MODEL_CALL, context(derived, "ctx_12"));
            execute(innerAgent, AgentCallbackEvent.BEFORE_MODEL_CALL, context(raw, "ctx"));
            execute(innerAgent, AgentCallbackEvent.BEFORE_MODEL_CALL, context(prefixCollision, "ctx_1_12"));
        }

        assertThat(derived.getTools()).extracting(ToolInfo::getName).containsExactly("readCurrentPage");
        assertThat(raw.getTools()).isEmpty();
        assertThat(prefixCollision.getTools()).isEmpty();
    }

    @Test
    void newVisibleToolCallProducesToolAwareClientInterrupt() {
        ReActAgent agent = agent();
        ToolCall toolCall = ToolCall.builder().id("call-1").name("readCurrentPage")
                .arguments("{\"selector\":\"#main\"}").build();

        try (ClientToolRail.Binding ignored = bind(agent, "ctx", List.of(clientTool("readCurrentPage")), null)) {
            assertThatThrownBy(() -> execute(agent, AgentCallbackEvent.BEFORE_TOOL_CALL,
                    toolContext(toolCall, "readCurrentPage", "ctx", null)))
                    .isInstanceOfSatisfying(ToolInterruptException.class, exception -> {
                        assertThat(exception.getRequest()).isInstanceOfSatisfying(
                                ToolCallInterruptRequest.class, request -> {
                                    assertThat(request.getToolCallId()).isEqualTo("call-1");
                                    assertThat(request.getToolName()).isEqualTo("readCurrentPage");
                                    assertThat(request.getContext())
                                            .containsEntry("_interrupt_kind", "client_tool")
                                            .containsEntry("arguments", Map.of("selector", "#main"));
                                });
                    });
        }
    }

    @Test
    void pendingCallUsesTargetedResumeInputAsToolResult() {
        ReActAgent agent = agent();
        ToolCall toolCall = ToolCall.builder().id("call-1").name("readCurrentPage").arguments("{}").build();
        InteractiveInput resume = new InteractiveInput();
        resume.setUserInputs(Map.of("call-1", "page body from client"));
        AgentCallbackContext context = toolContext(toolCall, "readCurrentPage", "ctx", resume);

        try (ClientToolRail.Binding ignored = bind(agent, "ctx", List.of(), singlePending())) {
            execute(agent, AgentCallbackEvent.BEFORE_TOOL_CALL, context);
        }

        assertThat(context.getExtra()).containsEntry("_skip_tool", true);
        assertThat(context.getInputs()).isInstanceOfSatisfying(ToolCallInputs.class, inputs -> {
            assertThat(inputs.getToolResult()).isEqualTo("page body from client");
            assertThat(inputs.getToolMsg().getToolCallId()).isEqualTo("call-1");
            assertThat(inputs.getToolMsg().getContent()).isEqualTo("page body from client");
        });
    }

    @Test
    void pendingOnlyNameDoesNotInterceptNewCallWithDifferentId() {
        ReActAgent agent = agent();
        ToolCall toolCall = ToolCall.builder().id("call-new").name("readCurrentPage").arguments("{}").build();
        AgentCallbackContext context = toolContext(toolCall, "readCurrentPage", "ctx", null);

        try (ClientToolRail.Binding ignored = bind(agent, "ctx", List.of(), singlePending())) {
            execute(agent, AgentCallbackEvent.BEFORE_TOOL_CALL, context);
        }

        assertThat(context.getExtra()).doesNotContainKey("_skip_tool");
        assertThat(context.getInputs()).isInstanceOfSatisfying(
                ToolCallInputs.class, inputs -> assertThat(inputs.getToolResult()).isNull());
    }

    @Test
    void unrelatedToolWithoutCallIdIsIgnored() {
        ReActAgent agent = agent();
        ToolCall toolCall = ToolCall.builder().name("serverTool").arguments("{}").build();
        AgentCallbackContext context = toolContext(toolCall, "serverTool", "ctx", null);

        try (ClientToolRail.Binding ignored = bind(agent, "ctx", List.of(clientTool("readCurrentPage")), null)) {
            assertThatCode(() -> execute(agent, AgentCallbackEvent.BEFORE_TOOL_CALL, context))
                    .doesNotThrowAnyException();
        }
    }

    private static ClientToolRail.Binding bind(Object agent, String conversationId,
            List<Map<String, Object>> tools, Map<String, Object> interrupt) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (tools != null && !tools.isEmpty()) {
            metadata.put("clientTools", tools);
        }
        if (interrupt != null) {
            metadata.put("_interrupt", interrupt);
        }
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setMetadata(metadata);
        return ClientToolRail.bind(agent, request);
    }

    private static void execute(BaseAgent agent, AgentCallbackEvent event, AgentCallbackContext context) {
        agent.getAgentCallbackManager().execute(event, context);
    }

    private static ReActAgent agent() {
        return new ReActAgent(agentCard());
    }

    private static AgentCard agentCard() {
        return AgentCard.builder().id("agent").name("Agent").description("Agent").build();
    }

    private static Map<String, Object> clientTool(String name) {
        return Map.of(
                "name", name,
                "description", "Read current page",
                "inputSchema", Map.of("type", "object"));
    }

    private static Map<String, Object> singlePending() {
        return Map.of(
                "type", "__interaction__",
                "toolCallId", "call-1",
                "toolName", "readCurrentPage",
                "context", Map.of("_interrupt_kind", "client_tool"));
    }

    private static AgentCallbackContext context(EventInputs inputs, String sessionId) {
        return AgentCallbackContext.builder().inputs(inputs).session(new TestSession(sessionId))
                .extra(new HashMap<>()).build();
    }

    private static AgentCallbackContext toolContext(ToolCall toolCall, String toolName, String sessionId,
            InteractiveInput resume) {
        AgentCallbackContext context = context(ToolCallInputs.builder()
                .toolCall(toolCall).toolName(toolName).build(), sessionId);
        if (resume != null) {
            context.getExtra().put(ToolInterruptionState.RESUME_USER_INPUT_KEY, resume);
        }
        return context;
    }

    private record TestSession(String sessionId) implements Session {
        @Override
        public String getSessionId() {
            return sessionId;
        }

        @Override
        public Object getState(String key) {
            return null;
        }

        @Override
        public void updateState(Map<String, Object> state) {
        }
    }
}
