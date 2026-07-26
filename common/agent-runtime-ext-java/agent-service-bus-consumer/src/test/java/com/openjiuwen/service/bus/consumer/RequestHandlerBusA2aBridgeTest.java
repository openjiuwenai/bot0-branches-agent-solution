/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests mapping of FEAT-001 A2A method names to the shared RequestHandler.
 *
 * @since 2026-07-22
 */
class RequestHandlerBusA2aBridgeTest {
    @Test
    void mapsFeat001MethodNamesToRequestHandler() {
        AtomicReference<String> called = new AtomicReference<>();
        AtomicReference<Boolean> streamFlag = new AtomicReference<>();
        RequestHandler handler = requestHandler(called, streamFlag);
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge(handler);

        var send = bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), bytes("""
                {"method":"SendMessage","params":{"message":{"role":"ROLE_USER",
                "parts":[{"text":"hi"}],"messageId":"m1"}}}
                """));
        assertThat(called).hasValue("onMessageSend");
        assertThat(streamFlag).hasValue(false);
        assertThat(send.task().id()).isEqualTo("task-1");

        var stream = bridge.handle(event("A2A_CALL_REQUESTED"), bytes("""
                {"method":"SendStreamingMessage","params":{"message":{"role":"ROLE_USER",
                "parts":[{"text":"hi"}],"messageId":"m1","taskId":"task-1"}}}
                """));
        assertThat(called).hasValue("onMessageSendStream");
        assertThat(streamFlag).hasValue(true);
        assertThat(stream.taskId()).isEqualTo("task-1");
        assertThat(stream.response()).isNull();
        assertThat(stream.streamReady()).isTrue();

        bridge.handle(event("CLIENT_INVOCATION_QUERY_REQUESTED"), bytes("""
                {"method":"GetTask","params":{"id":"task-1"}}
                """));
        assertThat(called).hasValue("onGetTask");
    }

    @Test
    void obtainsRuntimeTaskIdFromFirstEventOfNewStream() {
        RequestHandler handler = requestHandler(new AtomicReference<>(), new AtomicReference<>());
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge(handler);

        var stream = bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), bytes("""
                {"method":"SendStreamingMessage","params":{"message":{"role":"ROLE_USER",
                "parts":[{"text":"hi"}],"messageId":"m1"}}}
                """));

        assertThat(stream.taskId()).isEqualTo("task-1");
        assertThat(stream.streamReady()).isTrue();
    }

    @Test
    void extractsContinuationTaskIdAndMapsSubscribeToRequestHandler() {
        AtomicReference<String> called = new AtomicReference<>();
        RequestHandler handler = requestHandler(called);
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge(handler);

        assertThat(bridge.requestedTaskId(event("A2A_CALL_REQUESTED"), bytes("""
                {"method":"SendMessage","params":{"message":{"role":"ROLE_USER",
                "parts":[{"text":"hi"}],"messageId":"m1","taskId":"task-1"}}}
                """)).orElseThrow()).isEqualTo("task-1");

        bridge.handle(event("A2A_STREAM_SUBSCRIBE_REQUESTED"), bytes("""
                {"method":"SubscribeToTask","params":{"id":"task-1"}}
                """));
        assertThat(called).hasValue("onGetTask");
    }

    @Test
    void rejectsSubscriptionToTerminalTaskWithoutOpeningPublisher() {
        AtomicReference<String> called = new AtomicReference<>();
        RequestHandler handler = requestHandlerProxy((proxy, method, args) -> {
            called.set(method.getName());
            if ("onGetTask".equals(method.getName())) {
                return Task.builder().id("task-1").contextId("ctx")
                        .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
            }
            throw new AssertionError("Unexpected RequestHandler call: " + method.getName());
        });
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge(handler);

        assertThatThrownBy(() -> bridge.handle(event("CLIENT_STREAM_SUBSCRIBE_REQUESTED"), bytes("""
                {"method":"SubscribeToTask","params":{"id":"task-1"}}
                """))).isInstanceOf(UnsupportedOperationError.class);
        assertThat(called).hasValue("onGetTask");
    }

    @Test
    void rejectsUndocumentedLowercaseMethodAliases() {
        RequestHandler handler = requestHandler(new AtomicReference<>());
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge(handler);

        assertThatThrownBy(() -> bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), bytes("""
                {"method":"message/send","params":{"message":{"role":"ROLE_USER",
                "parts":[{"text":"hi"}],"messageId":"m1"}}}
                """))).isInstanceOf(InvalidRequestError.class);
        assertThatThrownBy(() -> bridge.handle(event("CLIENT_INVOCATION_QUERY_REQUESTED"), bytes("""
                {"method":"tasks/get","params":{"id":"task-1"}}
                """))).isInstanceOf(InvalidRequestError.class);
    }

    @Test
    void rejectsPayloadTenantThatDoesNotMatchEnvelope() {
        RequestHandler handler = requestHandlerProxy((proxy, method, args) -> task("task-1"));
        var bridge = new RequestHandlerBusA2aBridge(handler);
        assertThatThrownBy(() -> bridge.handle(event("CLIENT_INVOCATION_QUERY_REQUESTED"), bytes("""
                {"method":"GetTask","params":{"id":"task-1","tenant":"tenant-b"}}
                """))).isInstanceOf(IllegalArgumentException.class).hasMessage("TASK_NOT_FOUND");
    }

    private static Task task(String id) {
        return Task.builder().id(id).contextId("ctx").status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build();
    }

    private static RequestHandler requestHandler(AtomicReference<String> called) {
        return requestHandler(called, new AtomicReference<>());
    }

    private static RequestHandler requestHandler(AtomicReference<String> called, AtomicReference<Boolean> streamFlag) {
        return requestHandlerProxy((proxy, method, args) -> {
            called.set(method.getName());
            if ("onMessageSend".equals(method.getName()) || "onGetTask".equals(method.getName())) {
                if ("onMessageSend".equals(method.getName())) {
                    streamFlag.set(streamFlag(args));
                }
                return task("task-1");
            }
            if ("onMessageSendStream".equals(method.getName())) {
                streamFlag.set(streamFlag(args));
                return firstEventPublisher(TaskStatusUpdateEvent.builder().taskId("task-1").contextId("ctx")
                        .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build());
            }
            if ("onSubscribeToTask".equals(method.getName())) {
                return firstEventPublisher(task("task-1"));
            }
            return null;
        });
    }

    private static Boolean streamFlag(Object[] args) {
        return (Boolean) ((ServerCallContext) args[1]).getState().get("_a2a_stream");
    }

    private static Flow.Publisher<StreamingEventKind> firstEventPublisher(StreamingEventKind event) {
        return subscriber -> subscriber.onSubscribe(new Flow.Subscription() {
            private boolean emitted;

            @Override
            public void request(long count) {
                if (!emitted && count > 0) {
                    emitted = true;
                    subscriber.onNext(event);
                }
            }

            @Override
            public void cancel() {
                // The bridge only needs the first event to obtain the Task id.
            }
        });
    }

    private static RequestHandler requestHandlerProxy(java.lang.reflect.InvocationHandler invocationHandler) {
        Object candidate = Proxy.newProxyInstance(RequestHandlerBusA2aBridgeTest.class.getClassLoader(),
                new Class<?>[]{RequestHandler.class}, invocationHandler);
        if (candidate instanceof RequestHandler handler) {
            return handler;
        }
        throw new AssertionError("RequestHandler proxy has an incompatible type");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static AgentBusEventEnvelope event(String type) {
        return new AgentBusEventEnvelope("1.0", type, "message-1", "tenant-a", "source", "runtime-a", null, "corr-1",
                "trace-1", "idem-1", Instant.now().plusSeconds(60), "application/json", new byte[]{1}, null, Map.of());
    }
}
