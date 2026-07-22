/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;

/**
 * Bridges a host-defined REST request to the runtime A2A request handler and response projections.
 *
 * @since 0.1.0
 */
final class CustomRestA2ABridge {
    static final String STREAM_STATE_KEY = "_a2a_stream";

    private final CustomRestProtocolAdapter adapter;
    private final RequestHandler requestHandler;
    private final CustomRestA2ATaskResolver resolver;
    private final AgentReadiness readiness;
    private final ConcurrentHashMap<String, Object> reservations = new ConcurrentHashMap<>();

    CustomRestA2ABridge(CustomRestProtocolAdapter adapter, RequestHandler requestHandler, TaskStore taskStore,
                       AgentReadiness readiness) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");
        this.resolver = new CustomRestA2ATaskResolver(Objects.requireNonNull(taskStore, "taskStore"));
        this.readiness = readiness;
    }

    Prepared prepare(CustomRestProtocolAdapter.Context context, boolean acceptsSse) {
        CustomRestProtocolAdapter.A2ASendCommand command = adapter.toA2ARequest(context);
        validateCommand(command);
        if (command.stream() && !acceptsSse) {
            throw new CustomRestFailure(406, "stream_not_acceptable", "The request does not accept an SSE response");
        }
        if (readiness != null && !readiness.isAgentLoaded()) {
            throw new CustomRestFailure(503, "agent_not_ready", "The agent is not ready");
        }

        String tenantId = command.params().tenant();
        String internalContextId = CustomRestA2ATaskResolver.internalContextId(tenantId, command.conversationId());
        Object token = acquire(internalContextId);
        boolean preparedSuccessfully = false;
        try {
            Message original = command.params().message();
            String taskId = original.taskId();
            if (taskId == null) {
                taskId = resolver.resolveTaskId(tenantId, internalContextId).orElse(null);
            }
            Message rebuiltMessage = Message.builder(original)
                    .contextId(internalContextId)
                    .taskId(taskId)
                    .build();
            MessageSendParams rebuiltParams = MessageSendParams.builder()
                    .message(rebuiltMessage)
                    .configuration(command.params().configuration())
                    .metadata(command.params().metadata())
                    .tenant(tenantId)
                    .build();
            ServerCallContext callContext = new ServerCallContext(
                    UnauthenticatedUser.INSTANCE, Map.of(STREAM_STATE_KEY, command.stream()), Set.of());
            preparedSuccessfully = true;
            return new Prepared(command, context, rebuiltParams, callContext, internalContextId, token);
        } finally {
            if (!preparedSuccessfully) {
                release(internalContextId, token);
            }
        }
    }

    Object executeBlocking(Prepared prepared) {
        Task task;
        try {
            EventKind result = requestHandler.onMessageSend(prepared.params(), prepared.callContext());
            if (!(result instanceof Task)) {
                throw new CustomRestFailure(502, "invalid_a2a_result",
                        "The A2A runtime returned an invalid blocking result");
            }
            task = (Task) result;
        } catch (A2AError error) {
            throw mapA2AError(error);
        } catch (CustomRestFailure failure) {
            throw failure;
        } catch (RuntimeException error) {
            throw new CustomRestFailure(500, "adapter_execution_failed",
                    "The A2A runtime could not execute the request");
        } finally {
            release(prepared.internalContextId(), prepared.token());
        }
        return projectTask(task, prepared.httpContext());
    }

    Flow.Publisher<StreamingEventKind> executeStream(Prepared prepared) {
        try {
            return Objects.requireNonNull(requestHandler.onMessageSendStream(prepared.params(), prepared.callContext()),
                    "RequestHandler returned a null publisher");
        } catch (A2AError error) {
            release(prepared.internalContextId(), prepared.token());
            throw mapA2AError(error);
        } catch (RuntimeException error) {
            release(prepared.internalContextId(), prepared.token());
            throw new CustomRestFailure(500, "adapter_execution_failed", "The A2A stream could not be started");
        }
    }

    Object projectError(CustomRestFailure failure, CustomRestProtocolAdapter.Context context) {
        Object projected = adapter.fromError(failure.toError(), context);
        return projected != null ? projected : fallbackError(failure);
    }

    CustomRestProtocolAdapter.SseEvent projectStreamError(CustomRestFailure failure,
                                                           CustomRestProtocolAdapter.Context context) {
        CustomRestProtocolAdapter.SseEvent projected = adapter.fromStreamError(failure.toError(), context);
        return projected != null ? projected : fallbackSseError(failure);
    }

    CustomRestProtocolAdapter.SseEvent projectEvent(StreamingEventKind event,
                                                     CustomRestProtocolAdapter.Context context) {
        CustomRestProtocolAdapter.SseEvent projected = adapter.fromA2AStreamEvent(event, context);
        if (projected == null) {
            throw new CustomRestFailure(500, "adapter_execution_failed",
                    "The custom stream event could not be projected");
        }
        return projected;
    }

    CustomRestFailure streamFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CustomRestFailure failure) {
                return failure;
            }
            if (current instanceof A2AError error) {
                return mapA2AError(error);
            }
            current = current.getCause();
        }
        return new CustomRestFailure(500, "adapter_execution_failed", "The A2A stream failed");
    }

    boolean confirmObservable(String taskId, Prepared prepared) {
        return resolver.isObservableFormalParent(taskId, prepared.internalContextId());
    }

    void release(Prepared prepared) {
        release(prepared.internalContextId(), prepared.token());
    }

    private Object projectTask(Task task, CustomRestProtocolAdapter.Context context) {
        Object projected = adapter.fromA2ATask(task, context);
        if (projected == null) {
            throw new CustomRestFailure(500, "adapter_execution_failed",
                    "The custom response could not be projected");
        }
        return projected;
    }

    private Object acquire(String internalContextId) {
        Object token = new Object();
        if (reservations.putIfAbsent(internalContextId, token) != null) {
            throw new CustomRestFailure(409, "conversation_busy",
                    "The conversation is currently processing another request");
        }
        return token;
    }

    private void release(String internalContextId, Object token) {
        reservations.remove(internalContextId, token);
    }

    private static void validateCommand(CustomRestProtocolAdapter.A2ASendCommand command) {
        if (command == null || command.params() == null) {
            throw new CustomRestFailure(500, "adapter_execution_failed",
                    "The custom protocol adapter returned an invalid command");
        }
        if (command.conversationId() == null || command.conversationId().isBlank()) {
            throw new CustomRestFailure(400, "invalid_custom_request", "conversationId is required");
        }
    }

    private static CustomRestFailure mapA2AError(A2AError error) {
        int code = error.getCode() == null ? 0 : error.getCode();
        A2AErrorCodes known = A2AErrorCodes.fromCode(code);
        int status = known == null ? 500 : known.httpCode();
        return new CustomRestFailure(status, "a2a_" + code, "The A2A runtime rejected the request");
    }

    static Map<String, Object> fallbackError(CustomRestFailure failure) {
        return Map.of("error", Map.of("code", failure.getCode(), "message", failure.getMessage()));
    }

    static CustomRestProtocolAdapter.SseEvent fallbackSseError(CustomRestFailure failure) {
        return new CustomRestProtocolAdapter.SseEvent("error", fallbackError(failure));
    }

    static boolean isSerializable(ObjectMapper objectMapper, Object value) {
        try {
            objectMapper.writeValueAsBytes(value);
            return true;
        } catch (JsonProcessingException | RuntimeException exception) {
            return false;
        }
    }

    record Prepared(CustomRestProtocolAdapter.A2ASendCommand command,
                    CustomRestProtocolAdapter.Context httpContext,
                    MessageSendParams params,
                    ServerCallContext callContext,
                    String internalContextId,
                    Object token) {
    }
}
