/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
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

import org.springframework.beans.factory.ObjectProvider;

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
    private ObjectProvider<TaskAdmissionGate> admissionGateProvider;

    CustomRestA2ABridge(CustomRestProtocolAdapter adapter, RequestHandler requestHandler, TaskStore taskStore,
                       AgentReadiness readiness) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler");
        this.resolver = new CustomRestA2ATaskResolver(Objects.requireNonNull(taskStore, "taskStore"));
        this.readiness = readiness;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setAdmissionGateProvider(ObjectProvider<TaskAdmissionGate> admissionGateProvider) {
        this.admissionGateProvider = admissionGateProvider;
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
        if (isAdmissionOverloaded()) {
            throw new CustomRestFailure(503, "concurrent_limit_reached", "Concurrent task limit reached");
        }

        MessageSendParams preparedParams = command.params();
        Message original = preparedParams.message();
        String tenantId = preparedParams.tenant();
        String conversationId = original.contextId();
        Object token = acquire(conversationId);
        boolean preparedSuccessfully = false;
        try {
            if (original.taskId() == null) {
                String resolvedTaskId = resolver.resolveTaskId(tenantId, conversationId).orElse(null);
                if (resolvedTaskId != null) {
                    Message resumedMessage = Message.builder(original).taskId(resolvedTaskId).build();
                    preparedParams = new MessageSendParams(resumedMessage, preparedParams.configuration(),
                            preparedParams.metadata(), tenantId);
                }
            }
            preparedSuccessfully = true;
            return new Prepared(context, preparedParams, command.stream(), token);
        } finally {
            if (!preparedSuccessfully) {
                release(conversationId, token);
            }
        }
    }

    /**
     * Read-only admission pre-check for fast failure. Authoritative admission
     * happens in {@code A2AAgentExecutor.executeRequest()} — this check
     * carries no permit and therefore has no release obligation.
     */
    private boolean isAdmissionOverloaded() {
        if (admissionGateProvider == null) {
            return false;
        }
        TaskAdmissionGate admissionGate = admissionGateProvider.getIfAvailable();
        return admissionGate != null && admissionGate.limit() >= 0
                && admissionGate.currentCount() >= admissionGate.limit();
    }

    Object executeBlocking(Prepared prepared) {
        Task task;
        try {
            EventKind result = requestHandler.onMessageSend(prepared.params(), callContext(prepared.stream()));
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
                    "The A2A runtime could not execute the request", error);
        } finally {
            release(prepared.params().message().contextId(), prepared.token());
        }
        return projectTask(task, prepared.httpContext());
    }

    Flow.Publisher<StreamingEventKind> executeStream(Prepared prepared) {
        try {
            return Objects.requireNonNull(requestHandler.onMessageSendStream(
                            prepared.params(), callContext(prepared.stream())),
                    "RequestHandler returned a null publisher");
        } catch (A2AError error) {
            release(prepared.params().message().contextId(), prepared.token());
            throw mapA2AError(error);
        } catch (RuntimeException error) {
            release(prepared.params().message().contextId(), prepared.token());
            throw new CustomRestFailure(500, "adapter_execution_failed", "The A2A stream could not be started", error);
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
        return resolver.isObservableFormalParent(taskId, prepared.params().message().contextId());
    }

    void release(Prepared prepared) {
        release(prepared.params().message().contextId(), prepared.token());
    }

    private Object projectTask(Task task, CustomRestProtocolAdapter.Context context) {
        Object projected = adapter.fromA2ATask(task, context);
        if (projected == null) {
            throw new CustomRestFailure(500, "adapter_execution_failed",
                    "The custom response could not be projected");
        }
        return projected;
    }

    private Object acquire(String conversationId) {
        Object token = new Object();
        if (reservations.putIfAbsent(conversationId, token) != null) {
            throw new CustomRestFailure(409, "conversation_busy",
                    "The conversation is currently processing another request");
        }
        return token;
    }

    private void release(String conversationId, Object token) {
        reservations.remove(conversationId, token);
    }

    private static ServerCallContext callContext(boolean stream) {
        return new ServerCallContext(UnauthenticatedUser.INSTANCE, Map.of(STREAM_STATE_KEY, stream), Set.of());
    }

    private static void validateCommand(CustomRestProtocolAdapter.A2ASendCommand command) {
        if (command == null || command.params() == null) {
            throw new CustomRestFailure(500, "adapter_execution_failed",
                    "The custom protocol adapter returned an invalid command");
        }
        if (command.params().message().contextId() == null || command.params().message().contextId().isBlank()) {
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

    record Prepared(CustomRestProtocolAdapter.Context httpContext,
                    MessageSendParams params,
                    boolean stream,
                    Object token) {
    }
}
