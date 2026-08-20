/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;
import com.openjiuwen.service.app.controller.a2a.A2AJsonRpcSupport;

import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.InvalidRequestError;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.UnsupportedOperationError;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Bridges bus control events to the same public A2A RequestHandler used by HTTP.
 *
 * @since 2026-07-22
 */
public class RequestHandlerBusA2aBridge {
    private static final long FIRST_STREAM_EVENT_TIMEOUT_SECONDS = 30L;

    private final Supplier<RequestHandler> requestHandler;
    private final A2AJsonRpcSupport jsonRpcSupport;

    /**
     * Creates a new instance.
     *
     * @param requestHandler
     *            the requestHandler value
     */
    public RequestHandlerBusA2aBridge(RequestHandler requestHandler) {
        this(() -> Objects.requireNonNull(requestHandler, "requestHandler is required"));
    }

    /**
     * Creates a bridge whose handler is resolved lazily after Runtime auto-configuration.
     *
     * @param requestHandler lazy RequestHandler resolver
     */
    public RequestHandlerBusA2aBridge(Supplier<RequestHandler> requestHandler) {
        this.requestHandler = Objects.requireNonNull(requestHandler, "requestHandler resolver is required");
        this.jsonRpcSupport = new A2AJsonRpcSupport();
    }

    /**
     * Creates an unbound bridge for SDK-internal test doubles.
     */
    protected RequestHandlerBusA2aBridge() {
        this(() -> null);
    }

    /**
     * Dispatches a bus event through the shared A2A request handler.
     *
     * @param envelope
     *            validated event envelope
     * @param payload
     *            decoded request payload
     *
     * @return normalized A2A dispatch result
     */
    public BusDispatchResult handle(AgentBusEventEnvelope envelope, byte[] payload) {
        RequestHandler handler = requireRequestHandler();
        ServerCallContext context = context(envelope);
        A2AJsonRpcSupport.ParsedA2ARequest decoded = decode(payload, envelope.tenantId());
        return switch (envelope.eventType()) {
            case "CLIENT_INVOCATION_REQUESTED", "A2A_CALL_REQUESTED" -> send(envelope, decoded, context, handler);
            case "CLIENT_INVOCATION_QUERY_REQUESTED", "A2A_CALL_QUERY_REQUESTED" -> {
                requireMethod(decoded, "GetTask");
                TaskQueryParams params = params(decoded, TaskQueryParams.class);
                yield BusDispatchResult.task(handler.onGetTask(params, context));
            }
            case "CLIENT_STREAM_SUBSCRIBE_REQUESTED", "A2A_STREAM_SUBSCRIBE_REQUESTED" -> {
                requireMethod(decoded, "SubscribeToTask");
                TaskIdParams params = params(decoded, TaskIdParams.class);
                ensureSubscribable(params, context, handler);
                yield BusDispatchResult.stream(params.id());
            }
            default -> throw new InvalidRequestError("Unsupported A2A bus event: " + envelope.eventType());
        };
    }

    /**
     * Returns the existing Task id carried by a continuation request.
     *
     * @param envelope
     *            event envelope
     * @param payload
     *            request payload
     *
     * @return existing Task id, if present
     */
    public Optional<String> requestedTaskId(AgentBusEventEnvelope envelope, byte[] payload) {
        if (requestHandler.get() == null) {
            return Optional.empty();
        }
        if (!envelope.eventType().endsWith("INVOCATION_REQUESTED")
                && !"A2A_CALL_REQUESTED".equals(envelope.eventType())) {
            return Optional.empty();
        }
        MessageSendParams params = params(decode(payload, envelope.tenantId()), MessageSendParams.class);
        return Optional.ofNullable(params.message().taskId()).filter(value -> !value.isBlank());
    }

    /**
     * Returns the JSON-RPC request identity carried by an A2A bus payload.
     *
     * @param payload decoded request payload
     * @return exact non-null request identity
     */
    public Object requestId(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("PAYLOAD_EMPTY");
        }
        try {
            return jsonRpcSupport.parseRequestId(new String(payload, StandardCharsets.UTF_8));
        } catch (A2AJsonRpcSupport.RequestException failure) {
            throw failure.getError();
        }
    }

    /**
     * Serializes a bridge result through the same response serializer used by the HTTP controller.
     *
     * @param payload original A2A JSON-RPC request
     * @param result handler dispatch result
     * @return complete standard A2A JSON-RPC response
     */
    public String response(byte[] payload, BusDispatchResult result) {
        A2AJsonRpcSupport.ParsedA2ARequest decoded = decode(payload, null);
        try {
            if (isMethod(decoded, "GetTask")) {
                return A2aJsonRpcResponseSerializer.queryResult(decoded.originalId(), result.task());
            }
            return A2aJsonRpcResponseSerializer.sendMessage(decoded.originalId(), result.response());
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize A2A bridge response", failure);
        }
    }

    /**
     * Serializes an A2A error response (same serializer as the HTTP controller), carrying the client's
     * JSON-RPC request id decoded from the inline payload, so the gateway can pass the runtime's -32004
     * (or other) error through with the same semantics as the DIRECT (HTTP) path.
     *
     * @param code numeric A2A error code (e.g. A2AErrorCodes.UNSUPPORTED_OPERATION.code() = -32004)
     * @param message human-readable message
     * @param payload inline A2A JSON-RPC request (to recover the request id); null/blank means no id
     * @return complete standard A2A JSON-RPC error response
     */
    public String errorResponseJson(int code, String message, byte[] payload) {
        Object requestId = null;
        if (payload != null && payload.length > 0) {
            try {
                requestId = jsonRpcSupport.parseRequestId(new String(payload, StandardCharsets.UTF_8));
            } catch (RuntimeException ignore) {
                // Malformed payload: emit the error without an id as a best effort.
            }
        }
        A2AError error = new A2AError(code, message, null);
        try {
            return A2aJsonRpcResponseSerializer.serialize(new A2AErrorResponse(requestId, error));
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize A2A error response", failure);
        }
    }

    /**
     * Returns whether the upstream request handler accepts a caller-reserved Task id.
     *
     * @return {@code true} when reserved Task ids are supported
     */
    public boolean supportsReservedTaskId() {
        return false;
    }

    /**
     * Dispatches with a caller-reserved Task id when the upstream runtime supports it.
     *
     * @param envelope
     *            event envelope
     * @param payload
     *            request payload
     * @param reservedTaskId
     *            caller-reserved Task id
     *
     * @return normalized A2A dispatch result
     */
    public BusDispatchResult handle(AgentBusEventEnvelope envelope, byte[] payload, String reservedTaskId) {
        throw new IllegalStateException("A2A request handler does not support caller-reserved Task ids");
    }

    private BusDispatchResult send(AgentBusEventEnvelope envelope, A2AJsonRpcSupport.ParsedA2ARequest decoded,
            ServerCallContext context,
            RequestHandler handler)
            throws A2AError {
        MessageSendParams params = params(decoded, MessageSendParams.class);
        if (params.message().taskId() != null) {
            handler.validateRequestedTask(params.message().taskId());
        }
        boolean streaming = isMethod(decoded, "SendStreamingMessage") || decoded.method() == null
                && Boolean.parseBoolean(envelope.metadata() == null ? null : envelope.metadata().get("streaming"));
        context.getState().put("_a2a_stream", streaming);
        if (streaming) {
            Flow.Publisher<StreamingEventKind> publisher = handler.onMessageSendStream(params, context);
            return BusDispatchResult.streaming(firstEvent(publisher));
        }
        requireMethod(decoded, "SendMessage");
        return BusDispatchResult.response(handler.onMessageSend(params, context));
    }

    private static void ensureSubscribable(TaskIdParams params, ServerCallContext context, RequestHandler handler)
            throws A2AError {
        Task task = handler.onGetTask(new TaskQueryParams(params.id(), null, params.tenant()), context);
        if (task.status().state().isFinal()) {
            throw new UnsupportedOperationError(null,
                    "Cannot subscribe to task " + task.id() + " - task is in terminal state: "
                            + task.status().state(), null);
        }
    }

    private RequestHandler requireRequestHandler() {
        return Objects.requireNonNull(requestHandler.get(), "A2A RequestHandler is unavailable");
    }

    private static StreamingEventKind firstEvent(Flow.Publisher<StreamingEventKind> publisher) {
        if (publisher == null) {
            throw new IllegalStateException("A2A stream publisher is unavailable");
        }
        CompletableFuture<StreamingEventKind> first = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription value) {
                subscription = value;
                value.request(1);
            }

            @Override
            public void onNext(StreamingEventKind item) {
                first.complete(item);
                subscription.cancel();
            }

            @Override
            public void onError(Throwable failure) {
                first.completeExceptionally(failure);
            }

            @Override
            public void onComplete() {
                first.completeExceptionally(new IllegalStateException("A2A stream completed without an event"));
            }
        });
        try {
            return first.get(FIRST_STREAM_EVENT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            throw new IllegalStateException("Interrupted while waiting for the first A2A stream event", failure);
        } catch (ExecutionException failure) {
            throw new IllegalStateException("A2A stream failed before its first event", failure.getCause());
        } catch (TimeoutException failure) {
            throw new IllegalStateException("Timed out waiting for the first A2A stream event", failure);
        }
    }

    private static ServerCallContext context(AgentBusEventEnvelope envelope) {
        Map<String, Object> state = new HashMap<>();
        state.put("tenantId", envelope.tenantId());
        state.put("correlationId", envelope.correlationId());
        state.put("idempotencyKey", envelope.idempotencyKey());
        state.put("traceId", envelope.traceId());
        state.put("sourceServiceId", envelope.sourceServiceId());
        state.put("targetServiceId", envelope.targetServiceId());
        state.put("deadline", envelope.deadline());
        return new ServerCallContext(UnauthenticatedUser.INSTANCE, state, Set.of());
    }

    private A2AJsonRpcSupport.ParsedA2ARequest decode(byte[] payload, String expectedTenant) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("PAYLOAD_EMPTY");
        }
        try {
            return jsonRpcSupport.parseRequest(new String(payload, StandardCharsets.UTF_8), expectedTenant);
        } catch (A2AJsonRpcSupport.RequestException failure) {
            throw failure.getError();
        }
    }

    private static <T> T params(A2AJsonRpcSupport.ParsedA2ARequest request, Class<T> type) {
        if (!type.isInstance(request.params())) {
            throw new InvalidRequestError("A2A method does not match bus event");
        }
        return type.cast(request.params());
    }

    private static void requireMethod(A2AJsonRpcSupport.ParsedA2ARequest payload, String expected) {
        if (isMethod(payload, expected)) {
            return;
        }
        throw new InvalidRequestError("A2A method does not match bus event");
    }

    private static boolean isMethod(A2AJsonRpcSupport.ParsedA2ARequest payload, String expected) {
        return expected.equals(payload.method());
    }

}
