/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import java.util.Map;
import java.util.concurrent.Flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

class CustomRestSseTransportTest {
    @Test
    void subscribeFailureWritesOneErrorAndReleasesReservationOnce() {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        when(prepared.httpContext()).thenReturn(new CustomRestProtocolAdapter.Context(
                Map.of(), Map.of(), Map.of(), Map.of()));
        when(bridge.projectStreamError(any(), any())).thenReturn(
                new CustomRestProtocolAdapter.SseEvent("error", Map.of("error", "safe")));
        Flow.Publisher<StreamingEventKind> publisher = subscriber -> {
            throw new IllegalStateException("subscribe failed");
        };

        assertThatCode(() -> new CustomRestSseTransport(bridge, new ObjectMapper()).connect(publisher, prepared))
                .doesNotThrowAnyException();

        verify(bridge, times(1)).release(prepared);
        verify(bridge).projectStreamError(any(), any());
    }

    @Test
    void taskStoreFailureDuringFirstEventBecomesOneStreamErrorAndReleasesReservation() {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        when(prepared.httpContext()).thenReturn(new CustomRestProtocolAdapter.Context(
                Map.of(), Map.of(), Map.of(), Map.of()));
        when(bridge.confirmObservable(any(), any())).thenThrow(
                new CustomRestFailure(503, "task_store_unavailable", "The task store is unavailable"));
        when(bridge.projectStreamError(any(), any())).thenReturn(
                new CustomRestProtocolAdapter.SseEvent("error", Map.of("code", "task_store_unavailable")));
        ManualPublisher publisher = new ManualPublisher();
        new CustomRestSseTransport(bridge, new ObjectMapper()).connect(publisher, prepared);
        var event = new TaskStatusUpdateEvent("task", new TaskStatus(TaskState.TASK_STATE_WORKING),
                "context", Map.of());

        assertThatCode(() -> publisher.next(event)).doesNotThrowAnyException();
        verify(bridge).projectStreamError(any(), any());
        verify(bridge).release(prepared);
    }

    @Test
    void requestsOneEventAtATimeAndStopsAfterTerminalEvent() {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        when(prepared.httpContext()).thenReturn(new CustomRestProtocolAdapter.Context(
                Map.of(), Map.of(), Map.of(), Map.of()));
        when(bridge.confirmObservable(any(), any())).thenReturn(true);
        when(bridge.projectEvent(any(), any())).thenReturn(
                new CustomRestProtocolAdapter.SseEvent("chunk", Map.of("value", "ok")));
        ManualPublisher publisher = new ManualPublisher();
        new CustomRestSseTransport(bridge, new ObjectMapper()).connect(publisher, prepared);

        publisher.next(new TaskStatusUpdateEvent("task", new TaskStatus(TaskState.TASK_STATE_WORKING),
                "context", Map.of()));
        publisher.next(new TaskStatusUpdateEvent("task", new TaskStatus(TaskState.TASK_STATE_COMPLETED),
                "context", Map.of()));

        org.assertj.core.api.Assertions.assertThat(publisher.requested).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThat(publisher.cancelled).isTrue();
        verify(bridge, times(1)).release(prepared);
    }

    @Test
    void terminalEventDoesNotReleaseReservationBeforeParentIsObservable() {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        when(prepared.httpContext()).thenReturn(new CustomRestProtocolAdapter.Context(
                Map.of(), Map.of(), Map.of(), Map.of()));
        when(bridge.confirmObservable(any(), any())).thenReturn(false);
        when(bridge.projectEvent(any(), any())).thenReturn(
                new CustomRestProtocolAdapter.SseEvent("final", Map.of("value", "done")));
        ManualPublisher publisher = new ManualPublisher();
        new CustomRestSseTransport(bridge, new ObjectMapper()).connect(publisher, prepared);

        publisher.next(new TaskStatusUpdateEvent("task", new TaskStatus(TaskState.TASK_STATE_COMPLETED),
                "context", Map.of()));

        verify(bridge, never()).release(prepared);
        org.assertj.core.api.Assertions.assertThat(publisher.cancelled).isTrue();
    }

    @Test
    void nonSerializableAdapterEventBecomesStreamError() {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        when(prepared.httpContext()).thenReturn(new CustomRestProtocolAdapter.Context(
                Map.of(), Map.of(), Map.of(), Map.of()));
        when(bridge.confirmObservable(any(), any())).thenReturn(true);
        when(bridge.projectEvent(any(), any())).thenReturn(
                new CustomRestProtocolAdapter.SseEvent("chunk", new SelfReference()));
        when(bridge.projectStreamError(any(), any())).thenReturn(
                new CustomRestProtocolAdapter.SseEvent("error", Map.of("error", "safe")));
        ManualPublisher publisher = new ManualPublisher();
        new CustomRestSseTransport(bridge, new ObjectMapper()).connect(publisher, prepared);

        assertThatCode(() -> publisher.next(new TaskStatusUpdateEvent("task",
                new TaskStatus(TaskState.TASK_STATE_WORKING), "context", Map.of())))
                .doesNotThrowAnyException();

        verify(bridge).projectStreamError(any(), any());
        org.assertj.core.api.Assertions.assertThat(publisher.cancelled).isTrue();
    }

    @Test
    void nulInAdapterEventNameBecomesStreamError() {
        assertInvalidProjection(new CustomRestProtocolAdapter.SseEvent("bad\0name", Map.of("value", "ok")));
    }

    @Test
    void nullAdapterEventDataBecomesStreamError() {
        assertInvalidProjection(new CustomRestProtocolAdapter.SseEvent("chunk", null));
    }

    @Test
    void nestedEmitterAdapterEventDataBecomesStreamError() {
        assertInvalidProjection(new CustomRestProtocolAdapter.SseEvent("chunk",
                new org.springframework.web.servlet.mvc.method.annotation.SseEmitter()));
    }

    private static void assertInvalidProjection(CustomRestProtocolAdapter.SseEvent invalidEvent) {
        CustomRestA2ABridge bridge = mock(CustomRestA2ABridge.class);
        CustomRestA2ABridge.Prepared prepared = mock(CustomRestA2ABridge.Prepared.class);
        when(prepared.httpContext()).thenReturn(new CustomRestProtocolAdapter.Context(
                Map.of(), Map.of(), Map.of(), Map.of()));
        when(bridge.confirmObservable(any(), any())).thenReturn(true);
        when(bridge.projectEvent(any(), any())).thenReturn(invalidEvent);
        when(bridge.projectStreamError(any(), any())).thenReturn(
                new CustomRestProtocolAdapter.SseEvent("error", Map.of("error", "safe")));
        ManualPublisher publisher = new ManualPublisher();
        new CustomRestSseTransport(bridge, new ObjectMapper()).connect(publisher, prepared);

        assertThatCode(() -> publisher.next(new TaskStatusUpdateEvent("task",
                new TaskStatus(TaskState.TASK_STATE_WORKING), "context", Map.of())))
                .doesNotThrowAnyException();

        verify(bridge).projectStreamError(any(), any());
        org.assertj.core.api.Assertions.assertThat(publisher.cancelled).isTrue();
    }

    private static final class ManualPublisher implements Flow.Publisher<StreamingEventKind> {
        private Flow.Subscriber<? super StreamingEventKind> subscriber;
        private long requested;
        private boolean cancelled;

        @Override
        public void subscribe(Flow.Subscriber<? super StreamingEventKind> subscriber) {
            this.subscriber = subscriber;
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long count) {
                    requested += count;
                }

                @Override
                public void cancel() {
                    cancelled = true;
                }
            });
        }

        void next(StreamingEventKind event) {
            subscriber.onNext(event);
        }
    }

    private static final class SelfReference {
        public SelfReference getSelf() {
            return this;
        }
    }
}
