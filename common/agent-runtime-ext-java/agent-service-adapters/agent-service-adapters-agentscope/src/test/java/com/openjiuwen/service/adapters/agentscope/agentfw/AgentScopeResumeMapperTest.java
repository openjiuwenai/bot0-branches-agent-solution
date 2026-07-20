/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.dto.ServeRequest;

import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests runtime interaction conversion into AgentScope resume messages.
 *
 * @since 2026-07-20
 */
class AgentScopeResumeMapperTest {
    private static final String INTERRUPT = "_interrupt";

    @Test
    void appliesOneConfirmationResponseToAllAskingToolsInCurrentInterrupt() {
        ToolUseBlock first = tool("call-1", "transfer", ToolCallState.ASKING);
        ToolUseBlock second = tool("call-2", "notify", ToolCallState.ASKING);
        AgentState state = stateWith(first, second);
        ServeRequest request = request("APPROVE", "confirmation");

        List<Msg> messages = new AgentScopeResumeMapper().map(request, state);

        assertThat(messages).hasSize(1);
        Msg resume = messages.get(0);
        assertThat(resume.getRole()).isEqualTo(MsgRole.USER);
        assertThat(resume.getContent()).isEmpty();
        @SuppressWarnings("unchecked")
        List<ConfirmResult> results = (List<ConfirmResult>) resume.getMetadata().get(Msg.METADATA_CONFIRM_RESULTS);
        assertThat(results).extracting(ConfirmResult::isConfirmed).containsExactly(true, true);
        assertThat(results).extracting(result -> result.getToolCall().getId())
            .containsExactly("call-1", "call-2");
    }

    @Test
    void mapsCompleteExternalResultsToToolRoleMessage() {
        ToolUseBlock first = tool("call-1", "external_search", ToolCallState.PENDING);
        AgentState state = stateWith(first);
        ServeRequest request = request("found", "tool_result");

        Msg resume = new AgentScopeResumeMapper().map(request, state).get(0);

        assertThat(resume.getRole()).isEqualTo(MsgRole.TOOL);
        ToolResultBlock result = resume.getFirstContentBlock(ToolResultBlock.class);
        assertThat(result.getId()).isEqualTo("call-1");
        assertThat(result.getName()).isEqualTo("external_search");
        assertThat(result.getOutput()).singleElement().isInstanceOfSatisfying(
            TextBlock.class,
            block -> assertThat(block.getText()).isEqualTo("found"));
    }

    @Test
    void resumesMessageWithEmptyCallWhenInternalToolIsPending() {
        ToolUseBlock internal = tool("call-1", "search", ToolCallState.PENDING);

        List<Msg> messages = new AgentScopeResumeMapper()
            .map(request("continue", "message"), stateWith(internal));

        assertThat(messages).isEmpty();
    }

    @Test
    void resumesMessageInteractionWithEmptyCallWhenNoToolIsPending() {
        List<Msg> messages = new AgentScopeResumeMapper()
            .map(request("continue", "message"), stateWith());

        assertThat(messages).isEmpty();
    }

    @Test
    void rejectsNaturalLanguageAsConfirmationAction() {
        AgentState asking = stateWith(
            tool("call-1", "transfer", ToolCallState.ASKING),
            tool("call-2", "notify", ToolCallState.ASKING));

        assertThatThrownBy(() -> new AgentScopeResumeMapper().map(
            request("confirm", "confirmation"), asking))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("APPROVE or REJECT");
    }

    @Test
    void rejectsMultipleExternalPendingTools() {
        assertThatThrownBy(() -> new AgentScopeResumeMapper().map(
            request("result", "tool_result"), stateWith(
                tool("call-1", "search", ToolCallState.PENDING),
                tool("call-2", "lookup", ToolCallState.PENDING))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("exactly one external pending tool");
    }

    @Test
    void rejectsConfirmationActionWhenPendingStateNoLongerExists() {
        assertThatThrownBy(() -> new AgentScopeResumeMapper().map(
            request("APPROVE", "confirmation"), stateWith()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no pending confirmation");
    }

    @Test
    void rejectsResumeWhenTaskStoreInteractionKindIsMissing() {
        assertThatThrownBy(() -> new AgentScopeResumeMapper().map(request("continue", null), stateWith()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("interaction kind");
    }

    private static ToolUseBlock tool(String id, String name, ToolCallState state) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of()).state(state).build();
    }

    private static AgentState stateWith(ToolUseBlock... tools) {
        if (tools.length == 0) {
            return AgentState.builder().sessionId("session").userId("user").build();
        }
        Msg assistant = Msg.builder().role(MsgRole.ASSISTANT).content(tools).build();
        return AgentState.builder().sessionId("session").userId("user").addMessage(assistant).build();
    }

    private static ServeRequest request(String content, String interactionKind) {
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(Map.of("role", "user", "content", content)));
        request.getMetadata().put(INTERRUPT,
            interactionKind == null ? Map.of() : Map.of("payload", Map.of("kind", interactionKind)));
        return request;
    }
}
