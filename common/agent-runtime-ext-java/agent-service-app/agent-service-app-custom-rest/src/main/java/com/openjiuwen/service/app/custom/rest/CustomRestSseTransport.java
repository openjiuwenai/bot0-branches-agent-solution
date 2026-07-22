/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import java.io.IOException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletResponse;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

final class CustomRestSseTransport {
    private final CustomRestA2ABridge bridge;
    private final ObjectMapper objectMapper;

    CustomRestSseTransport(CustomRestA2ABridge bridge, ObjectMapper objectMapper) {
        this.bridge = bridge;
        this.objectMapper = objectMapper;
    }

    SseEmitter connect(Flow.Publisher<StreamingEventKind> publisher, CustomRestA2ABridge.Prepared prepared) {
        SseEmitter emitter = new SseEmitter(0L);
        StreamSubscriber subscriber = new StreamSubscriber(emitter, prepared);
        emitter.onCompletion(subscriber::downstreamClosed);
        emitter.onTimeout(subscriber::downstreamClosed);
        emitter.onError(error -> subscriber.downstreamClosed());
        try {
            publisher.subscribe(subscriber);
        } catch (RuntimeException exception) {
            subscriber.writeError(new CustomRestFailure(500, "adapter_execution_failed",
                    "The A2A stream could not be subscribed"));
        }
        return emitter;
    }

    private final class StreamSubscriber implements Flow.Subscriber<StreamingEventKind> {
        private final SseEmitter emitter;
        private final CustomRestA2ABridge.Prepared prepared;
        private final AtomicBoolean terminated = new AtomicBoolean();
        private final AtomicBoolean reservationReleased = new AtomicBoolean();
        private volatile Flow.Subscription subscription;
        private volatile boolean downstreamClosed;

        private StreamSubscriber(SseEmitter emitter, CustomRestA2ABridge.Prepared prepared) {
            this.emitter = emitter;
            this.prepared = prepared;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (this.subscription != null) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(StreamingEventKind event) {
            if (terminated.get()) {
                return;
            }
            try {
                tryReleaseReservation(event);
                if (downstreamClosed) {
                    requestOrCancelAfterDisconnect();
                    return;
                }
                CustomRestProtocolAdapter.SseEvent projected = bridge.projectEvent(event, prepared.httpContext());
                if (!isValid(projected)) {
                    throw new CustomRestFailure(500, "adapter_execution_failed",
                            "The custom stream event is invalid");
                }
                emitter.send(toEmitterEvent(projected));
                if (isTerminal(event)) {
                    finish();
                } else {
                    subscription.request(1);
                }
            } catch (IOException exception) {
                downstreamClosed = true;
                requestOrCancelAfterDisconnect();
            } catch (CustomRestFailure failure) {
                writeError(failure);
            } catch (RuntimeException exception) {
                writeError(new CustomRestFailure(500, "adapter_execution_failed",
                        "The custom stream event could not be written"));
            }
        }

        @Override
        public void onError(Throwable throwable) {
            releaseReservation();
            if (!terminated.get() && !downstreamClosed) {
                writeError(bridge.streamFailure(throwable));
            } else {
                terminated.set(true);
            }
        }

        @Override
        public void onComplete() {
            releaseReservation();
            if (terminated.compareAndSet(false, true) && !downstreamClosed) {
                emitter.complete();
            }
        }

        private void downstreamClosed() {
            downstreamClosed = true;
            if (reservationReleased.get() && subscription != null) {
                subscription.cancel();
            }
        }

        private void tryReleaseReservation(StreamingEventKind event) {
            String taskId = taskId(event);
            if (!reservationReleased.get() && taskId != null && bridge.confirmObservable(taskId, prepared)) {
                releaseReservation();
            }
        }

        private void releaseReservation() {
            if (reservationReleased.compareAndSet(false, true)) {
                bridge.release(prepared);
            }
        }

        private void requestOrCancelAfterDisconnect() {
            if (reservationReleased.get()) {
                subscription.cancel();
                terminated.set(true);
            } else {
                subscription.request(1);
            }
        }

        private void finish() {
            if (terminated.compareAndSet(false, true)) {
                subscription.cancel();
                emitter.complete();
            }
        }

        private void writeError(CustomRestFailure failure) {
            releaseReservation();
            if (!terminated.compareAndSet(false, true)) {
                return;
            }
            if (subscription != null) {
                subscription.cancel();
            }
            if (downstreamClosed) {
                return;
            }
            try {
                CustomRestProtocolAdapter.SseEvent projected = bridge.projectStreamError(failure,
                        prepared.httpContext());
                if (!isValid(projected)) {
                    projected = CustomRestA2ABridge.fallbackSseError(failure);
                }
                emitter.send(toEmitterEvent(projected));
                emitter.complete();
            } catch (IOException | RuntimeException exception) {
                emitter.completeWithError(exception);
            }
        }

        private boolean isValid(CustomRestProtocolAdapter.SseEvent event) {
            if (event == null || event.data() == null) {
                return false;
            }
            String name = event.event();
            if (name != null && (name.indexOf('\r') >= 0 || name.indexOf('\n') >= 0 || name.indexOf('\0') >= 0)) {
                return false;
            }
            Object data = event.data();
            if (data instanceof ResponseEntity<?> || data instanceof ResponseBodyEmitter
                    || data instanceof ServletResponse || data instanceof ModelAndView) {
                return false;
            }
            return CustomRestA2ABridge.isSerializable(objectMapper, data);
        }

        private SseEmitter.SseEventBuilder toEmitterEvent(CustomRestProtocolAdapter.SseEvent event) {
            SseEmitter.SseEventBuilder builder = SseEmitter.event();
            if (event.event() != null && !event.event().isEmpty()) {
                builder.name(event.event());
            }
            return builder.data(event.data());
        }

        private static String taskId(StreamingEventKind event) {
            if (event instanceof Task task) {
                return task.id();
            }
            if (event instanceof TaskStatusUpdateEvent status) {
                return status.taskId();
            }
            if (event instanceof TaskArtifactUpdateEvent artifact) {
                return artifact.taskId();
            }
            return null;
        }

        private static boolean isTerminal(StreamingEventKind event) {
            if (event instanceof TaskStatusUpdateEvent status) {
                return status.isFinalOrInterrupted();
            }
            if (event instanceof Task task) {
                return task.status() != null && task.status().state() != null
                        && (task.status().state().isFinal() || task.status().state().isInterrupted());
            }
            return false;
        }
    }
}
