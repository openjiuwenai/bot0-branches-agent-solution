package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteA2aInterruptRailTest {

    @Test
    void exposesToolCardAndInterruptsWithA2aDelegateContext() {
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b")));
        ToolCall toolCall = ToolCall.builder()
                .id("call-1")
                .name("agent-b")
                .arguments("{\"remoteInput\":\"find hotels\"}")
                .build();
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(toolCall)
                .toolName("agent-b")
                .build();
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new java.util.HashMap<>())
                .build();

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOf(ToolInterruptException.class)
                .satisfies(error -> {
                    ToolInterruptException exception = (ToolInterruptException) error;
                    assertThat(exception.getRequest().getMessage()).isEqualTo("find hotels");
                    assertThat(exception.getRequest().getContext())
                            .containsEntry("agentName", "agent-b")
                            .containsEntry("_interrupt_kind", "a2a_delegate")
                            .containsEntry("_stream_mode", "sse");
                });

        assertThat(rail.getTools()).singleElement().satisfies(card -> {
            assertThat(card.getName()).isEqualTo("agent-b");
            assertThat(card.getInputParams()).containsEntry("type", "object");
        });
    }

    @Test
    void resumeRejectsRemoteResultSoAbilityManagerSkipsRealToolLookup() {
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b")));
        ToolCall toolCall = ToolCall.builder()
                .id("call-1")
                .name("agent-b")
                .arguments("{\"remoteInput\":\"find hotels\"}")
                .build();
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(toolCall)
                .toolName("agent-b")
                .build();
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new java.util.HashMap<>(Map.of("_resume_user_input", "remote result")))
                .build();

        rail.beforeToolCall(ctx);

        assertThat(ctx.getExtra()).containsEntry("_skip_tool", Boolean.TRUE);
        assertThat(inputs.getToolResult()).isEqualTo("remote result");
        assertThat(inputs.getToolMsg()).isNotNull();
        assertThat(inputs.getToolMsg().getToolCallId()).isEqualTo("call-1");
    }

    @Test
    void dispatchesMultipleRemoteToolsByToolName() {
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b"), spec("agent-c")));
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(ToolCall.builder()
                        .id("call-1")
                        .name("agent-c")
                        .arguments("{\"remoteInput\":\"ask c\"}")
                        .build())
                .toolName("agent-c")
                .build();
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new java.util.HashMap<>())
                .build();

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOf(ToolInterruptException.class)
                .satisfies(error -> assertThat(((ToolInterruptException) error).getRequest().getContext())
                        .containsEntry("agentName", "agent-c"));
        assertThat(rail.getTools()).extracting(card -> card.getName())
                .containsExactlyInAnyOrder("agent-b", "agent-c");
    }

    @Test
    void dispatchesByToolInputsNameWhenToolCallNameDiffers() {
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b"), spec("agent-c")));
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(ToolCall.builder()
                        .id("call-1")
                        .name("agent-b")
                        .arguments("{\"remoteInput\":\"ask c\"}")
                        .build())
                .toolName("agent-c")
                .build();
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new java.util.HashMap<>())
                .build();

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOf(ToolInterruptException.class)
                .satisfies(error -> assertThat(((ToolInterruptException) error).getRequest().getContext())
                        .containsEntry("agentName", "agent-c"));
    }

    @Test
    void fallsBackToRawArgumentsWhenArgumentsAreNotJson() {
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b")));
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(ToolCall.builder().id("call-1").name("agent-b").arguments("plain text").build())
                .toolName("agent-b")
                .build();
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new java.util.HashMap<>())
                .build();

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOf(ToolInterruptException.class)
                .satisfies(error -> assertThat(((ToolInterruptException) error).getRequest().getMessage())
                        .isEqualTo("plain text"));
    }

    @Test
    void preservesStructuredRemoteInputStringAsOpaqueMessage() {
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b")));
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(ToolCall.builder()
                        .id("call-1")
                        .name("agent-b")
                        .arguments("{\"remoteInput\":\"{\\\"query\\\":\\\"find balance\\\",\\\"intent\\\":\\\"查询账户余额\\\"}\"}")
                        .build())
                .toolName("agent-b")
                .build();
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new java.util.HashMap<>())
                .build();

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOf(ToolInterruptException.class)
                .satisfies(error -> {
                    ToolInterruptException exception = (ToolInterruptException) error;
                    assertThat(exception.getRequest().getMessage())
                            .isEqualTo("{\"query\":\"find balance\",\"intent\":\"查询账户余额\"}");
                });
    }

    private static RemoteA2aAgentCardCache.RemoteA2aToolSpec spec(String name) {
        return new RemoteA2aAgentCardCache.RemoteA2aToolSpec(
                name,
                name,
                "Delegate to remote agent",
                Map.of("type", "object"));
    }
}
