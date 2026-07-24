/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.external;

import com.openjiuwen.core.foundation.llm.schema.ToolCall;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests remote A2A interrupt rail tool-call delegation.
 *
 * @since 2026-06-30
 */
class RemoteA2aInterruptRailTest {
    @Tag("smoke")
    @Test
    void exposesToolCardAndInterruptsWithA2aDelegateContext() {
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
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b")));

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOfSatisfying(ToolInterruptException.class, exception -> {
                    assertThat(exception.getRequest().getMessage()).isEqualTo("find hotels");
                    assertThat(exception.getRequest().getContext())
                            .containsEntry("agentName", "agent-b")
                            .containsEntry("_interrupt_kind", "a2a_delegate")
                            .doesNotContainKey("_stream_mode");
                });

        assertThat(rail.getTools()).singleElement().satisfies(card -> {
            assertThat(card.getName()).isEqualTo("agent-b");
            assertThat(card.getInputParams()).containsEntry("type", "object");
        });
    }

    @Test
    void resumeRejectsRemoteResultSoAbilityManagerSkipsRealToolLookup() {
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
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b")));

        rail.beforeToolCall(ctx);

        assertThat(ctx.getExtra()).containsEntry("_skip_tool", Boolean.TRUE);
        assertThat(inputs.getToolResult()).isEqualTo("remote result");
        assertThat(inputs.getToolMsg()).isNotNull();
        assertThat(inputs.getToolMsg().getToolCallId()).isEqualTo("call-1");
    }

    @Test
    void dispatchesMultipleRemoteToolsByToolName() {
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
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b"), spec("agent-c")));

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOfSatisfying(ToolInterruptException.class, exception -> assertThat(
                                exception.getRequest().getContext())
                        .containsEntry("agentName", "agent-c"));
        assertThat(rail.getTools()).extracting(card -> card.getName())
                .containsExactlyInAnyOrder("agent-b", "agent-c");
    }

    @Test
    void dispatchesByToolInputsNameWhenToolCallNameDiffers() {
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
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b"), spec("agent-c")));

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOfSatisfying(ToolInterruptException.class, exception -> assertThat(
                                exception.getRequest().getContext())
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
                .isInstanceOfSatisfying(ToolInterruptException.class, exception -> assertThat(
                                exception.getRequest().getMessage())
                        .isEqualTo("plain text"));
    }

    @Test
    void preservesStructuredRemoteInputStringAsOpaqueMessage() {
        ToolCallInputs inputs = ToolCallInputs.builder()
                .toolCall(ToolCall.builder()
                        .id("call-1")
                        .name("agent-b")
                        .arguments("{\"remoteInput\":\"{\\\"query\\\":\\\"find balance\\\","
                                + "\\\"intent\\\":\\\"查询账户余额\\\"}\"}")
                        .build())
                .toolName("agent-b")
                .build();
        AgentCallbackContext ctx = AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new java.util.HashMap<>())
                .build();
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(List.of(spec("agent-b")));

        assertThatThrownBy(() -> rail.beforeToolCall(ctx))
                .isInstanceOfSatisfying(ToolInterruptException.class, exception -> {
                    assertThat(exception.getRequest().getMessage())
                            .isEqualTo("{\"query\":\"find balance\",\"intent\":\"查询账户余额\"}");
                });
    }

    @Test
    void oneRailInstanceKeepsConcurrentToolCallInterruptsIsolated() throws Exception {
        RemoteA2aInterruptRail rail = new RemoteA2aInterruptRail(
                List.of(spec("agent-a"), spec("agent-b"), spec("agent-c")));

        List<CompletableFuture<ToolInterruptException>> calls = IntStream.range(0, 3)
                .mapToObj(index -> CompletableFuture.supplyAsync(() -> captureInterrupt(rail, index)))
                .toList();
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);

        List<ToolInterruptException> interrupts = calls.stream().map(CompletableFuture::join).toList();
        for (int index = 0; index < interrupts.size(); index++) {
            String suffix = String.valueOf((char) ('a' + index));
            ToolInterruptException interrupt = interrupts.get(index);
            assertThat(interrupt.getRequest().getMessage()).isEqualTo("message-" + suffix);
            assertThat(interrupt.getRequest().getContext()).containsEntry("agentName", "agent-" + suffix);
        }
    }

    private static ToolInterruptException captureInterrupt(RemoteA2aInterruptRail rail, int index) {
        String suffix = String.valueOf((char) ('a' + index));
        ToolCall toolCall = ToolCall.builder()
                .id("call-" + suffix)
                .name("agent-" + suffix)
                .arguments("{\"remoteInput\":\"message-" + suffix + "\"}")
                .build();
        ToolCallInputs inputs = ToolCallInputs.builder().toolCall(toolCall).toolName(toolCall.getName()).build();
        AgentCallbackContext context = AgentCallbackContext.builder()
                .inputs(inputs)
                .extra(new java.util.HashMap<>())
                .build();
        try {
            rail.beforeToolCall(context);
            throw new AssertionError("Expected remote interrupt");
        } catch (ToolInterruptException exception) {
            return exception;
        }
    }

    private static RemoteA2aToolInstaller.RemoteA2aToolSpec spec(String name) {
        return new RemoteA2aToolInstaller.RemoteA2aToolSpec(
                name,
                name,
                "Delegate to remote agent",
                Map.of("type", "object"));
    }
}
