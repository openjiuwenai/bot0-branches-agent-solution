/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies Custom REST request bridging and runtime error mapping.
 *
 * @since 0.1.0
 */
class CustomRestA2ABridgeTest {
    @Test
    void rebuildsFrameworkOwnedFieldsAndUsesBlockingRequestHandler() {
        CustomRestProtocolAdapter adapter = mock(CustomRestProtocolAdapter.class);
        RequestHandler handler = mock(RequestHandler.class);
        TaskStore store = mock(TaskStore.class);
        when(store.list(any())).thenReturn(new ListTasksResult(List.of()));
        Message original = Message.builder().role(Message.Role.ROLE_USER).parts(new TextPart("hello"))
                .messageId("message-id").contextId("adapter-context").metadata(Map.of("message", "kept")).build();
        MessageSendParams params = MessageSendParams.builder().message(original)
                .metadata(Map.of("request", "kept")).tenant("tenant").build();
        when(adapter.toA2ARequest(any())).thenReturn(
                new CustomRestProtocolAdapter.A2ASendCommand(params, "external-conversation", false));
        Task result = Task.builder().id("task").contextId("context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).history(List.of()).build();
        when(handler.onMessageSend(any(), any())).thenReturn(result);
        when(adapter.fromA2ATask(result, context())).thenReturn(Map.of("ok", true));
        CustomRestA2ABridge bridge = new CustomRestA2ABridge(adapter, handler, store, null);

        assertThat(bridge.executeBlocking(bridge.prepare(context(), true))).isEqualTo(Map.of("ok", true));

        ArgumentCaptor<MessageSendParams> paramsCaptor = ArgumentCaptor.forClass(MessageSendParams.class);
        ArgumentCaptor<org.a2aproject.sdk.server.ServerCallContext> contextCaptor =
                ArgumentCaptor.forClass(org.a2aproject.sdk.server.ServerCallContext.class);
        verify(handler).onMessageSend(paramsCaptor.capture(), contextCaptor.capture());
        assertThat(paramsCaptor.getValue().message().contextId()).startsWith("custom-rest:v1:");
        assertThat(paramsCaptor.getValue().message().contextId()).isNotEqualTo("adapter-context");
        assertThat(paramsCaptor.getValue().message().metadata()).containsEntry("message", "kept");
        assertThat(paramsCaptor.getValue().metadata()).containsEntry("request", "kept");
        assertThat(paramsCaptor.getValue().tenant()).isEqualTo("tenant");
        assertThat(contextCaptor.getValue().getState()).containsEntry(CustomRestA2ABridge.STREAM_STATE_KEY, false);
    }

    @Test
    void validatesConversationBeforeAcquiringReservation() {
        CustomRestProtocolAdapter adapter = mock(CustomRestProtocolAdapter.class);
        Message message = Message.builder().role(Message.Role.ROLE_USER).parts(new TextPart("hello")).build();
        when(adapter.toA2ARequest(any())).thenReturn(new CustomRestProtocolAdapter.A2ASendCommand(
                MessageSendParams.builder().message(message).build(), " ", false));
        CustomRestA2ABridge bridge = new CustomRestA2ABridge(adapter, mock(RequestHandler.class),
                mock(TaskStore.class), null);

        assertThatThrownBy(() -> bridge.prepare(context(), true))
                .isInstanceOf(CustomRestFailure.class)
                .extracting("code").isEqualTo("invalid_custom_request");
    }

    @Test
    void releasesBlockingReservationBeforeProjectingTheResponse() {
        CustomRestProtocolAdapter adapter = mock(CustomRestProtocolAdapter.class);
        RequestHandler handler = mock(RequestHandler.class);
        TaskStore store = mock(TaskStore.class);
        when(store.list(any())).thenReturn(new ListTasksResult(List.of()));
        Message message = Message.builder().role(Message.Role.ROLE_USER).parts(new TextPart("hello")).build();
        var command = new CustomRestProtocolAdapter.A2ASendCommand(
                MessageSendParams.builder().message(message).build(), "conversation", false);
        when(adapter.toA2ARequest(any())).thenReturn(command);
        Task task = Task.builder().id("task").contextId("context")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).history(List.of()).build();
        when(handler.onMessageSend(any(), any())).thenReturn(task);
        AtomicReference<CustomRestA2ABridge> bridgeRef = new AtomicReference<>();
        AtomicReference<CustomRestA2ABridge.Prepared> next = new AtomicReference<>();
        when(adapter.fromA2ATask(task, context())).thenAnswer(invocation -> {
            next.set(bridgeRef.get().prepare(context(), true));
            return Map.of("ok", true);
        });
        CustomRestA2ABridge bridge = new CustomRestA2ABridge(adapter, handler, store, null);
        bridgeRef.set(bridge);

        assertThat(bridge.executeBlocking(bridge.prepare(context(), true))).isEqualTo(Map.of("ok", true));
        bridge.release(next.get());
    }

    @Test
    void mapsUnexpectedRequestHandlerFailureToStableFrameworkError() {
        CustomRestProtocolAdapter adapter = mock(CustomRestProtocolAdapter.class);
        RequestHandler handler = mock(RequestHandler.class);
        TaskStore store = mock(TaskStore.class);
        when(store.list(any())).thenReturn(new ListTasksResult(List.of()));
        Message message = Message.builder().role(Message.Role.ROLE_USER).parts(new TextPart("hello")).build();
        when(adapter.toA2ARequest(any())).thenReturn(new CustomRestProtocolAdapter.A2ASendCommand(
                MessageSendParams.builder().message(message).build(), "conversation", false));
        when(handler.onMessageSend(any(), any())).thenThrow(new IllegalStateException("internal detail"));
        CustomRestA2ABridge bridge = new CustomRestA2ABridge(adapter, handler, store, null);

        assertThatThrownBy(() -> bridge.executeBlocking(bridge.prepare(context(), true)))
                .isInstanceOf(CustomRestFailure.class)
                .extracting("code").isEqualTo("adapter_execution_failed");
    }

    @Test
    void mapsWrappedStreamingA2AErrorWithoutLeakingItsMessage() {
        CustomRestA2ABridge bridge = new CustomRestA2ABridge(mock(CustomRestProtocolAdapter.class),
                mock(RequestHandler.class), mock(TaskStore.class), null);

        CustomRestFailure failure = bridge.streamFailure(new java.util.concurrent.CompletionException(
                new A2AError(-32602, "sensitive detail", Map.of())));

        assertThat(failure.getHttpStatus()).isEqualTo(422);
        assertThat(failure.getCode()).isEqualTo("a2a_-32602");
        assertThat(failure.getMessage()).doesNotContain("sensitive detail");
    }

    private static CustomRestProtocolAdapter.Context context() {
        return new CustomRestProtocolAdapter.Context(Map.of(), Map.of("id", "42"), Map.of(), Map.of("input", "x"));
    }
}
