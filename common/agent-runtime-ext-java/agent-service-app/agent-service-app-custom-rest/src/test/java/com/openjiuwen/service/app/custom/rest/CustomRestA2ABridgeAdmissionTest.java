/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * Unit tests for the read-only admission pre-check in
 * {@link CustomRestA2ABridge}. The bridge never acquires or releases a
 * permit — authoritative admission happens in
 * {@code A2AAgentExecutor.executeRequest()}.
 *
 * @since 0.1.2
 */
class CustomRestA2ABridgeAdmissionTest {

    @Test
    void prepare_rejectedWith503_whenLimitReached() {
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(1);
        when(gate.currentCount()).thenReturn(1);
        CustomRestA2ABridge bridge = newBridge(mock(RequestHandler.class), gate);

        assertThatThrownBy(() -> bridge.prepare(context(), true))
                .isInstanceOfSatisfying(CustomRestFailure.class,
                        f -> assertThat(f.getHttpStatus()).isEqualTo(503));
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void prepare_underLimit_executesWithoutGateRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(), any())).thenAnswer(invocation -> completedTask());
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        Object result = bridge.executeBlocking(bridge.prepare(context(), true));

        assertThat(result).isEqualTo(Map.of("ok", true));
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void prepare_unlimitedLimit_skipsPreCheck() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(), any())).thenAnswer(invocation -> completedTask());
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(-1);
        // Extreme count proves the pre-check was skipped (not merely passed):
        // a non-skipped check against MAX_VALUE would reject with 503.
        when(gate.currentCount()).thenReturn(Integer.MAX_VALUE);
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        // Execute fully — the request must succeed, not just pass prepare()
        Object result = bridge.executeBlocking(bridge.prepare(context(), true));

        assertThat(result).isEqualTo(Map.of("ok", true));
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void prepare_failure_noGateRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
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

        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void executeBlocking_a2aError_mappedWithoutGateRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(), any())).thenThrow(new A2AError(-32000, "task not found", null));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        assertThatThrownBy(() -> bridge.executeBlocking(bridge.prepare(context(), true)))
                .isInstanceOf(CustomRestFailure.class);

        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void executeBlocking_invalidResult_throws502_withoutGateRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(), any())).thenReturn(mock(EventKind.class));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        CustomRestA2ABridge bridge = newBridge(handler, gate);

        assertThatThrownBy(() -> bridge.executeBlocking(bridge.prepare(context(), true)))
                .isInstanceOfSatisfying(CustomRestFailure.class,
                        f -> assertThat(f.getHttpStatus()).isEqualTo(502));

        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void executeStream_exception_withoutGateRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSendStream(any(), any())).thenThrow(new RuntimeException("stream failed"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        CustomRestA2ABridge bridge = newStreamingBridge(handler, gate);

        assertThatThrownBy(() -> bridge.executeStream(bridge.prepare(context(), true)))
                .isInstanceOf(CustomRestFailure.class);

        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void executeStream_success_returnsPublisher_withoutGateRelease() {
        RequestHandler handler = mock(RequestHandler.class);
        Flow.Publisher<StreamingEventKind> publisher = subscriber -> {/* no-op */};
        when(handler.onMessageSendStream(any(), any())).thenReturn(publisher);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(5);
        CustomRestA2ABridge bridge = newStreamingBridge(handler, gate);

        Flow.Publisher<StreamingEventKind> result = bridge.executeStream(bridge.prepare(context(), true));

        assertThat(result).isSameAs(publisher);
        verify(gate, never()).tryAcquire();
        verify(gate, never()).release();
    }

    @Test
    void admissionGateNull_skipsCheck() {
        RequestHandler handler = mock(RequestHandler.class);
        when(handler.onMessageSend(any(), any())).thenAnswer(invocation -> completedTask());
        ObjectProvider<TaskAdmissionGate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        CustomRestA2ABridge bridge = newBridgeWithoutGate(handler);
        bridge.setAdmissionGateProvider(provider);

        // Execute fully — behavior must be unchanged when no gate bean exists
        Object result = bridge.executeBlocking(bridge.prepare(context(), true));

        assertThat(result).isEqualTo(Map.of("ok", true));
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
