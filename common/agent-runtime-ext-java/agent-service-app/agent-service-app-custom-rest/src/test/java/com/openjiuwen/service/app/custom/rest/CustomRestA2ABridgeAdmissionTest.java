/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * Unit tests for admission gate integration in {@link CustomRestA2ABridge}
 * (DFX-002 U-40~U-45).
 *
 * @since 0.1.2
 */
class CustomRestA2ABridgeAdmissionTest {

    @Test
    void prepare_rejectedWith503_whenLimitReached() {
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(false);
        CustomRestA2ABridge bridge = newBridge(mock(RequestHandler.class), gate);

        assertThatThrownBy(() -> bridge.prepare(context(), true))
                .isInstanceOfSatisfying(CustomRestFailure.class,
                        f -> assertThat(f.getHttpStatus()).isEqualTo(503));
    }

    @Test
    void prepare_acquired_releasedOnSuccess() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        when(handler.onMessageSend(any(), any())).thenAnswer(invocation -> {
            gate.release();
            return completedTask();
        });
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        bridge.executeBlocking(bridge.prepare(context(), true));

        verify(gate).release();
    }

    @Test
    void prepare_compensatingRelease_onExecuteException() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        when(handler.onMessageSend(any(), any())).thenThrow(new RuntimeException("handler failed"));
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        assertThatThrownBy(() -> bridge.executeBlocking(bridge.prepare(context(), true)))
                .isInstanceOf(CustomRestFailure.class);

        verify(gate).release();
    }

    @Test
    void prepare_compensatingRelease_onStreamException() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        when(handler.onMessageSendStream(any(), any())).thenThrow(new RuntimeException("stream failed"));
        CustomRestA2ABridge bridge = newStreamingBridge(handler, gate);

        assertThatThrownBy(() -> bridge.executeStream(bridge.prepare(context(), true)))
                .isInstanceOf(CustomRestFailure.class);

        verify(gate).release();
    }

    @Test
    void admissionGateNull_skipsCheck() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(), any())).thenReturn(completedTask());
        ObjectProvider<TaskAdmissionGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        CustomRestA2ABridge bridge = newBridgeWithoutGate(handler);
        bridge.setAdmissionGateProvider(provider);

        CustomRestA2ABridge.Prepared prepared = bridge.prepare(context(), true);
        assertThat(prepared).isNotNull();
    }

    @Test
    void prepare_acquiredFlag_preventsDoubleRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        when(handler.onMessageSend(any(), any())).thenAnswer(invocation -> {
            gate.release();
            return completedTask();
        });
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        bridge.executeBlocking(bridge.prepare(context(), true));

        verify(gate, times(1)).release();
    }

    @Test
    void executeBlocking_a2aError_compensatingRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        when(handler.onMessageSend(any(), any())).thenThrow(new A2AError(-32000, "task not found", null));
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        assertThatThrownBy(() -> bridge.executeBlocking(bridge.prepare(context(), true)))
                .isInstanceOf(CustomRestFailure.class);

        verify(gate).release();
    }

    @Test
    void executeBlocking_invalidResult_throws502() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        when(handler.onMessageSend(any(), any())).thenAnswer(invocation -> {
            gate.release();
            return mock(EventKind.class);
        });
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        assertThatThrownBy(() -> bridge.executeBlocking(bridge.prepare(context(), true)))
                .isInstanceOfSatisfying(CustomRestFailure.class,
                        f -> assertThat(f.getHttpStatus()).isEqualTo(502));

        verify(gate, times(1)).release();
    }

    @Test
    void executeStream_a2aError_compensatingRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        when(handler.onMessageSendStream(any(), any())).thenThrow(new A2AError(-32000, "task not found", null));
        CustomRestA2ABridge bridge = newStreamingBridge(handler, gate);

        assertThatThrownBy(() -> bridge.executeStream(bridge.prepare(context(), true)))
                .isInstanceOf(CustomRestFailure.class);

        verify(gate).release();
    }

    @Test
    void executeStream_success_returnsPublisher() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        Flow.Publisher<StreamingEventKind> publisher = subscriber -> { };
        when(handler.onMessageSendStream(any(), any())).thenReturn(publisher);
        CustomRestA2ABridge bridge = newStreamingBridge(handler, gate);

        Flow.Publisher<StreamingEventKind> result = bridge.executeStream(bridge.prepare(context(), true));

        assertThat(result).isSameAs(publisher);
        verify(gate, never()).release();
    }

    @Test
    void prepare_failure_compensatingRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        CustomRestProtocolAdapter adapter = mock(CustomRestProtocolAdapter.class);
        TaskStore store = mock(TaskStore.class);
        when(store.list(any())).thenThrow(new RuntimeException("store unavailable"));
        when(adapter.toA2ARequest(any())).thenReturn(command(false));
        CustomRestA2ABridge bridge = new CustomRestA2ABridge(adapter, handler, store, null);
        ObjectProvider<TaskAdmissionGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        bridge.setAdmissionGateProvider(provider);

        assertThatThrownBy(() -> bridge.prepare(context(), true))
                .isInstanceOf(RuntimeException.class);

        verify(gate).release();
    }

    @Test
    void executeStream_exception_nullGate_releaseAdmissionNoOp() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSendStream(any(), any())).thenThrow(new RuntimeException("stream failed"));
        CustomRestA2ABridge bridge = configureBridge(handler, null, true);

        assertThatThrownBy(() -> bridge.executeStream(bridge.prepare(context(), true)))
                .isInstanceOf(CustomRestFailure.class);
    }

    private static CustomRestA2ABridge newBridge(RequestHandler handler, TaskAdmissionGate gate) {
        return configureBridge(handler, gate, false);
    }

    private static CustomRestA2ABridge newStreamingBridge(RequestHandler handler, TaskAdmissionGate gate) {
        return configureBridge(handler, gate, true);
    }

    private static CustomRestA2ABridge newBridgeWithoutGate(RequestHandler handler) {
        CustomRestProtocolAdapter adapter = mock(CustomRestProtocolAdapter.class);
        TaskStore store = mock(TaskStore.class);
        when(store.list(any())).thenReturn(new ListTasksResult(List.of()));
        when(adapter.toA2ARequest(any())).thenReturn(command(false));
        when(adapter.fromA2ATask(any(), any())).thenReturn(Map.of("ok", true));
        return new CustomRestA2ABridge(adapter, handler, store, null);
    }

    private static CustomRestA2ABridge configureBridge(RequestHandler handler, TaskAdmissionGate gate,
            boolean stream) {
        CustomRestProtocolAdapter adapter = mock(CustomRestProtocolAdapter.class);
        TaskStore store = mock(TaskStore.class);
        when(store.list(any())).thenReturn(new ListTasksResult(List.of()));
        when(adapter.toA2ARequest(any())).thenReturn(command(stream));
        when(adapter.fromA2ATask(any(), any())).thenReturn(Map.of("ok", true));
        CustomRestA2ABridge bridge = new CustomRestA2ABridge(adapter, handler, store, null);
        ObjectProvider<TaskAdmissionGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gate);
        bridge.setAdmissionGateProvider(provider);
        return bridge;
    }

    private static CustomRestProtocolAdapter.A2ASendCommand command(boolean stream) {
        Message msg = Message.builder().role(Message.Role.ROLE_USER)
                .parts(new TextPart("hello")).messageId("msg-1")
                .contextId("conv-1").build();
        MessageSendParams params = MessageSendParams.builder().message(msg).build();
        return new CustomRestProtocolAdapter.A2ASendCommand(params, stream);
    }

    private static CustomRestProtocolAdapter.Context context() {
        return new CustomRestProtocolAdapter.Context(Map.of(), Map.of("id", "42"), Map.of(), Map.of("input", "x"));
    }

    private static Task completedTask() {
        return Task.builder().id("task-1").contextId("conv-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
    }
}
