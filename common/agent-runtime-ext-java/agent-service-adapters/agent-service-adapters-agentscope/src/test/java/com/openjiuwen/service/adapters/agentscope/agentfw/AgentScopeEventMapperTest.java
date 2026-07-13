/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentscope.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.dto.QueryChunk;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.RequestStopEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

class AgentScopeEventMapperTest {
    @Test
    void mapsTextDeltaToAnswerChunk() {
        QueryChunk chunk = new AgentScopeEventMapper().map(
            new TextBlockDeltaEvent("reply", "block", "hello"),
            AgentScopeEventMapperTest::stateWith,
            new AgentScopeEventMapper.StreamState()).orElseThrow();

        assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_CHUNK);
        assertThat(chunk.getData()).isEqualTo(Map.of("type", "answer_delta", "content", "hello"));
    }

    @Test
    void mapsConfirmationAndDeduplicatesFollowingPermissionStopPerStream() {
        ToolUseBlock tool = tool("call-1", "transfer", ToolCallState.ASKING);
        AgentScopeEventMapper mapper = new AgentScopeEventMapper();
        AgentScopeEventMapper.StreamState streamState = new AgentScopeEventMapper.StreamState();

        QueryChunk chunk = mapper.map(
            new RequireUserConfirmEvent("reply", List.of(tool)), () -> stateWith(tool), streamState).orElseThrow();

        assertInteraction(chunk, "confirmation");
        assertThat(mapper.map(
            new RequestStopEvent("permission", GenerateReason.PERMISSION_ASKING),
            () -> stateWith(tool),
            streamState)).isEmpty();
        assertThat(mapper.map(
            new RequestStopEvent("standalone", GenerateReason.MIDDLEWARE_STOP_REQUESTED),
            () -> stateWith(tool),
            new AgentScopeEventMapper.StreamState())).isPresent();
    }

    @Test
    void permissionStopWithoutPrecedingConfirmationEventUsesCurrentAskingState() {
        ToolUseBlock tool = tool("call-1", "transfer", ToolCallState.ASKING);
        AgentScopeEventMapper mapper = new AgentScopeEventMapper();
        AgentScopeEventMapper.StreamState streamState = new AgentScopeEventMapper.StreamState();

        QueryChunk chunk = mapper.map(
            new RequestStopEvent("permission", GenerateReason.PERMISSION_ASKING),
            () -> stateWith(tool),
            streamState).orElseThrow();

        assertInteraction(chunk, "confirmation");

        Msg paused = Msg.builder().role(MsgRole.ASSISTANT)
            .generateReason(GenerateReason.PERMISSION_ASKING)
            .build();
        assertThat(mapper.map(new AgentResultEvent(paused), () -> stateWith(tool), streamState)).isEmpty();
    }

    @Test
    void rejectsConfirmationEventThatDoesNotMatchCurrentAskingState() {
        ToolUseBlock pending = tool("call-1", "transfer", ToolCallState.ASKING);
        ToolUseBlock stale = tool("stale", "transfer", ToolCallState.ASKING);

        assertThatThrownBy(() -> new AgentScopeEventMapper().map(
            new RequireUserConfirmEvent("reply", List.of(stale)),
            () -> stateWith(pending),
            new AgentScopeEventMapper.StreamState()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ASKING");
    }

    @Test
    void mapsExternalSuspensionFromFinalResultWhenNoDedicatedEventIsEmitted() {
        ToolUseBlock tool = tool("call-1", "external_search", ToolCallState.PENDING);
        Msg suspended = Msg.builder().role(MsgRole.ASSISTANT)
            .generateReason(GenerateReason.TOOL_SUSPENDED).build();

        QueryChunk chunk = new AgentScopeEventMapper().map(
            new AgentResultEvent(suspended),
            () -> stateWith(tool),
            new AgentScopeEventMapper.StreamState()).orElseThrow();

        assertInteraction(chunk, "tool_result");
    }

    @Test
    void rejectsMultipleExternalPendingCallsInStreamingSuspension() {
        ToolUseBlock first = tool("call-1", "external_search", ToolCallState.PENDING);
        ToolUseBlock second = tool("call-2", "external_lookup", ToolCallState.PENDING);
        Msg suspended = Msg.builder().role(MsgRole.ASSISTANT)
            .generateReason(GenerateReason.TOOL_SUSPENDED).build();

        assertThatThrownBy(() -> new AgentScopeEventMapper().map(
            new AgentResultEvent(suspended),
            () -> stateWith(first, second),
            new AgentScopeEventMapper.StreamState()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exactly one external pending tool call");
    }

    @Test
    void mapsLegacyReasoningStopFromFinalResultWhenNoRequestStopEventIsEmitted() {
        Msg paused = Msg.builder().role(MsgRole.ASSISTANT)
            .textContent("Review before acting")
            .generateReason(GenerateReason.REASONING_STOP_REQUESTED)
            .build();

        QueryChunk chunk = new AgentScopeEventMapper().map(
            new AgentResultEvent(paused),
            AgentScopeEventMapperTest::stateWith,
            new AgentScopeEventMapper.StreamState()).orElseThrow();

        assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_INTERRUPT);
        @SuppressWarnings("unchecked")
        Map<String, Object> interaction = (Map<String, Object>) chunk.getData();
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) interaction.get("payload");
        assertThat(payload).containsEntry("kind", "message");
        assertThat(payload.get("items")).isEqualTo(List.of());
    }

    @Test
    void deduplicatesStandaloneStopAndItsFinalResult() {
        AgentScopeEventMapper mapper = new AgentScopeEventMapper();
        AgentScopeEventMapper.StreamState streamState = new AgentScopeEventMapper.StreamState();
        Msg paused = Msg.builder().role(MsgRole.ASSISTANT)
            .textContent("Review before acting")
            .generateReason(GenerateReason.MIDDLEWARE_STOP_REQUESTED)
            .build();

        assertThat(mapper.map(
            new RequestStopEvent("Review before acting"), AgentScopeEventMapperTest::stateWith, streamState)).isPresent();
        assertThat(mapper.map(
            new AgentResultEvent(paused), AgentScopeEventMapperTest::stateWith, streamState)).isEmpty();
    }

    @Test
    void doesNotMapAllToolsDeniedStopEventAsAnotherInterrupt() {
        assertThat(new AgentScopeEventMapper().map(
            new RequestStopEvent("all denied", GenerateReason.ALL_TOOLS_DENIED),
            AgentScopeEventMapperTest::stateWith,
            new AgentScopeEventMapper.StreamState())).isEmpty();
    }

    @Test
    void ignoresNonPauseEvents() {
        Msg result = Msg.builder().role(MsgRole.ASSISTANT).textContent("done").build();

        assertThat(new AgentScopeEventMapper().map(
            new AgentResultEvent(result),
            AgentScopeEventMapperTest::stateWith,
            new AgentScopeEventMapper.StreamState())).isEmpty();
    }

    @Test
    void treatsStreamingInterruptedGenerateReasonAsCancellation() {
        Msg interrupted = Msg.builder()
            .role(MsgRole.ASSISTANT)
            .generateReason(GenerateReason.INTERRUPTED)
            .build();

        assertThatThrownBy(() -> new AgentScopeEventMapper().map(
            new AgentResultEvent(interrupted),
            AgentScopeEventMapperTest::stateWith,
            new AgentScopeEventMapper.StreamState()))
            .isInstanceOf(CancellationException.class);
    }

    @Test
    void rejectsStreamingToolCallsAsUnsupportedTerminalResult() {
        Msg toolCalls = Msg.builder()
            .role(MsgRole.ASSISTANT)
            .generateReason(GenerateReason.TOOL_CALLS)
            .build();

        assertThatThrownBy(() -> new AgentScopeEventMapper().map(
            new AgentResultEvent(toolCalls),
            AgentScopeEventMapperTest::stateWith,
            new AgentScopeEventMapper.StreamState()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TOOL_CALLS");
    }

    @Test
    void mapsNonStreamingResultFromGenerateReasonAndState() {
        ToolUseBlock asking = tool("call-1", "transfer", ToolCallState.ASKING);
        Msg paused = Msg.builder()
            .role(MsgRole.ASSISTANT)
            .textContent("approval needed")
            .generateReason(GenerateReason.PERMISSION_ASKING)
            .build();

        Map<String, Object> mapped = new AgentScopeEventMapper().mapResult(paused, stateWith(asking));

        assertThat(mapped).containsEntry("role", "assistant").containsEntry("content", "approval needed");
        assertThat(mapped.get("_interrupt")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> interaction = (Map<String, Object>) mapped.get("_interrupt");
        assertThat(interaction.get("payload")).isEqualTo(Map.of(
            "kind", "confirmation",
            "items", List.of(Map.of("type", "tool_call", "name", "transfer", "arguments", Map.of("amount", 5)))));
    }

    @Test
    void treatsInterruptedGenerateReasonAsCancellation() {
        Msg interrupted = Msg.builder()
            .role(MsgRole.ASSISTANT)
            .textContent("cancelled")
            .generateReason(GenerateReason.INTERRUPTED)
            .build();

        assertThatThrownBy(() -> new AgentScopeEventMapper().mapResult(interrupted, stateWith()))
            .isInstanceOf(CancellationException.class);
    }

    @Test
    void treatsAllToolsDeniedAsTerminalResultInsteadOfAnotherInterrupt() {
        Msg denied = Msg.builder()
            .role(MsgRole.ASSISTANT)
            .textContent("All requested tools were denied")
            .generateReason(GenerateReason.ALL_TOOLS_DENIED)
            .build();

        Map<String, Object> mapped = new AgentScopeEventMapper().mapResult(denied, stateWith());

        assertThat(mapped)
            .containsEntry("content", "All requested tools were denied")
            .doesNotContainKey("_interrupt");
    }

    private static void assertInteraction(QueryChunk chunk, String kind) {
        assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_INTERRUPT);
        @SuppressWarnings("unchecked")
        Map<String, Object> interaction = (Map<String, Object>) chunk.getData();
        assertThat(interaction)
            .containsEntry("type", "__interaction__")
            .containsEntry("index", 0)
            .containsKeys("payload", "message", "context")
            .doesNotContainKeys("version", "kind", "items");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) interaction.get("payload");
        assertThat(payload).containsEntry("kind", kind);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
            .containsKeys("name", "arguments")
            .doesNotContainKey("id"));
    }

    private static ToolUseBlock tool(String id, String name, ToolCallState state) {
        return ToolUseBlock.builder().id(id).name(name).input(Map.of("amount", 5)).state(state).build();
    }

    private static AgentState stateWith(ToolUseBlock... tools) {
        AgentState.Builder builder = AgentState.builder().sessionId("session").userId("user");
        if (tools.length > 0) {
            builder.addMessage(Msg.builder().role(MsgRole.ASSISTANT).content(tools).build());
        }
        return builder.build();
    }
}
