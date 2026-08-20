/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.controller.a2a.A2aJsonRpcController;
import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

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
    void serializesBusResponseWithTheRuntimeHttpSerializer() throws Exception {
        Task responseTask = task("task-1");
        RequestHandler handler = requestHandlerProxy((proxy, method, args) -> responseTask);
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge(handler);
        A2aJsonRpcController controller = new A2aJsonRpcController(handler);
        byte[] payload = bytes("""
                {"jsonrpc":"2.0","id":"request-1","method":"SendMessage","params":{"message":{
                "role":"ROLE_USER","parts":[{"text":"hi"}],"messageId":"m1"}}}
                """);

        var result = bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), payload);
        var httpResponse = controller.handleJsonRpc(new String(payload, java.nio.charset.StandardCharsets.UTF_8),
                new MockHttpServletRequest("POST", "/a2a"));

        assertThat(bridge.requestId(payload)).isEqualTo("request-1");
        assertThat(bridge.response(payload, result)).isEqualTo(httpResponse.getBody());
    }

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
    void defaultsMissingNullOrBlankRoleToUserAndPreservesExplicitRole() {
        AtomicReference<Message.Role> receivedRole = new AtomicReference<>();
        RequestHandler handler = requestHandlerProxy((proxy, method, args) -> {
            if ("onMessageSend".equals(method.getName())) {
                if (args[0] instanceof org.a2aproject.sdk.spec.MessageSendParams params) {
                    receivedRole.set(params.message().role());
                }
                return task("task-1");
            }
            return null;
        });
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge(handler);

        bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), bytes("""
                {"method":"SendMessage","params":{"message":{
                "parts":[{"text":"missing"}],"messageId":"m-missing"}}}
                """));
        assertThat(receivedRole).hasValue(Message.Role.ROLE_USER);

        bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), bytes("""
                {"method":"SendMessage","params":{"message":{"role":null,
                "parts":[{"text":"null"}],"messageId":"m-null"}}}
                """));
        assertThat(receivedRole).hasValue(Message.Role.ROLE_USER);

        bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), bytes("""
                {"method":"SendMessage","params":{"message":{"role":" ",
                "parts":[{"text":"blank"}],"messageId":"m-blank"}}}
                """));
        assertThat(receivedRole).hasValue(Message.Role.ROLE_USER);

        bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), bytes("""
                {"method":"SendMessage","params":{"message":{"role":"ROLE_AGENT",
                "parts":[{"text":"agent"}],"messageId":"m-agent"}}}
                """));
        assertThat(receivedRole).hasValue(Message.Role.ROLE_AGENT);
    }

    @Test
    void rejectsInvalidExplicitRole() {
        RequestHandlerBusA2aBridge bridge = new RequestHandlerBusA2aBridge(requestHandler(new AtomicReference<>()));

        assertThatThrownBy(() -> bridge.handle(event("CLIENT_INVOCATION_REQUESTED"), bytes("""
                {"method":"SendMessage","params":{"message":{"role":"user",
                "parts":[{"text":"invalid"}],"messageId":"m-invalid"}}}
                """))).isInstanceOf(org.a2aproject.sdk.spec.InvalidParamsError.class);
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
        assertThat(bridge.requestedTaskId(event("A2A_CALL_REQUESTED"), bytes("""
                {"method":"SendMessage","params":{"message":{
                "parts":[{"text":"hi"}],"messageId":"m2","taskId":"task-2"}}}
                """)).orElseThrow()).isEqualTo("task-2");

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
                """))).isInstanceOf(org.a2aproject.sdk.spec.MethodNotFoundError.class);
        assertThatThrownBy(() -> bridge.handle(event("CLIENT_INVOCATION_QUERY_REQUESTED"), bytes("""
                {"method":"tasks/get","params":{"id":"task-1"}}
                """))).isInstanceOf(org.a2aproject.sdk.spec.MethodNotFoundError.class);
    }

    @Test
    void rejectsPayloadTenantThatDoesNotMatchEnvelope() {
        RequestHandler handler = requestHandlerProxy((proxy, method, args) -> task("task-1"));
        var bridge = new RequestHandlerBusA2aBridge(handler);
        assertThatThrownBy(() -> bridge.handle(event("CLIENT_INVOCATION_QUERY_REQUESTED"), bytes("""
                {"method":"GetTask","params":{"id":"task-1","tenant":"tenant-b"}}
                """))).isInstanceOf(org.a2aproject.sdk.spec.InvalidParamsError.class)
                .hasMessageContaining("tenant does not match");
    }

    @Test
    void preservesRequestIdWhenParamsAreInvalid() {
        var bridge = new RequestHandlerBusA2aBridge(requestHandler(new AtomicReference<>()));
        byte[] payload = bytes("""
                {"jsonrpc":"2.0","id":4294967297,"method":"GetTask",
                 "params":{"id":"task-1","unknownField":true}}
                """);

        assertThat(bridge.requestId(payload)).isEqualTo(new java.math.BigInteger("4294967297"));
        assertThat(bridge.errorResponseJson(-32602, "Invalid params", payload))
                .contains("\"id\":4294967297");
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
                    streamFlag.set(streamFlag(args[1]));
                }
                return task("task-1");
            }
            if ("onMessageSendStream".equals(method.getName())) {
                streamFlag.set(streamFlag(args[1]));
                return firstEventPublisher(TaskStatusUpdateEvent.builder().taskId("task-1").contextId("ctx")
                        .status(new TaskStatus(TaskState.TASK_STATE_WORKING)).build());
            }
            if ("onSubscribeToTask".equals(method.getName())) {
                return firstEventPublisher(task("task-1"));
            }
            return null;
        });
    }

    private static Boolean streamFlag(Object candidate) {
        if (candidate instanceof ServerCallContext context
                && context.getState().get("_a2a_stream") instanceof Boolean streamFlag) {
            return streamFlag;
        }
        throw new AssertionError("ServerCallContext does not contain the stream flag");
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
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode request =
                    (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(value);
            if (!request.has("jsonrpc")) {
                request.put("jsonrpc", "2.0");
            }
            if (!request.has("id")) {
                request.put("id", "request-test");
            }
            return mapper.writeValueAsBytes(request);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static AgentBusEventEnvelope event(String type) {
        return new AgentBusEventEnvelope("1.0", type, "message-1", "tenant-a", "source", "runtime-a", null, "corr-1",
                "trace-1", "idem-1", Instant.now().plusSeconds(60), "application/json", new byte[]{1}, null, Map.of());
    }
}
