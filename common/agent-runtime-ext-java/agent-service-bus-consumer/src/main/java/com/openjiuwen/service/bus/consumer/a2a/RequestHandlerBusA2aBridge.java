/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
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

import java.io.IOException;
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
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long FIRST_STREAM_EVENT_TIMEOUT_SECONDS = 30L;
    private static final String DEFAULT_MESSAGE_ROLE = "ROLE_USER";

    private final Supplier<RequestHandler> requestHandler;

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
        Payload decoded = decode(payload);
        return switch (envelope.eventType()) {
            case "CLIENT_INVOCATION_REQUESTED", "A2A_CALL_REQUESTED" -> send(envelope, decoded, context, handler);
            case "CLIENT_INVOCATION_QUERY_REQUESTED", "A2A_CALL_QUERY_REQUESTED" -> {
                requireMethod(decoded, "GetTask");
                TaskQueryParams params = scoped(convert(decoded.params(), TaskQueryParams.class), envelope.tenantId());
                yield BusDispatchResult.task(handler.onGetTask(params, context));
            }
            case "CLIENT_STREAM_SUBSCRIBE_REQUESTED", "A2A_STREAM_SUBSCRIBE_REQUESTED" -> {
                requireMethod(decoded, "SubscribeToTask");
                TaskIdParams params = scoped(convert(decoded.params(), TaskIdParams.class), envelope.tenantId());
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
        MessageSendParams params = messageSendParams(decode(payload).params());
        return Optional.ofNullable(params.message().taskId()).filter(value -> !value.isBlank());
    }

    /**
     * Returns the JSON-RPC request identity carried by an A2A bus payload.
     *
     * @param payload decoded request payload
     * @return request identity, or {@code null} when the request is a notification
     */
    public Object requestId(byte[] payload) {
        return decode(payload).requestId();
    }

    /**
     * Serializes a bridge result through the same response serializer used by the HTTP controller.
     *
     * @param payload original A2A JSON-RPC request
     * @param result handler dispatch result
     * @return complete standard A2A JSON-RPC response
     */
    public String response(byte[] payload, BusDispatchResult result) {
        Payload decoded = decode(payload);
        try {
            if (isMethod(decoded, "GetTask")) {
                return A2aJsonRpcResponseSerializer.queryResult(decoded.requestId(), result.task());
            }
            return A2aJsonRpcResponseSerializer.sendMessage(decoded.requestId(), result.response());
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException failure) {
            throw new IllegalStateException("Failed to serialize A2A bridge response", failure);
        }
    }

    /**
     * Serializes an A2A error response (same serializer as the HTTP controller), carrying the client's
     * JSON-RPC request id decoded from the inline payload, so the gateway can pass the runtime's -32004
     * (or other) error through verbatim ¡ª byte-identical to the DIRECT (HTTP) path.
     *
     * @param code numeric A2A error code (e.g. A2AErrorCodes.UNSUPPORTED_OPERATION.code() = -32004)
     * @param message human-readable message
     * @param payload inline A2A JSON-RPC request (to recover the request id); null/blank ¡ú no id
     * @return complete standard A2A JSON-RPC error response
     */
    public String errorResponseJson(int code, String message, byte[] payload) {
        Object requestId = null;
        if (payload != null && payload.length > 0) {
            try {
                requestId = decode(payload).requestId();
            } catch (RuntimeException ignore) {
                // malformed/un-decodable payload ¡ú emit the error without an id (best effort)
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

    private BusDispatchResult send(AgentBusEventEnvelope envelope, Payload decoded, ServerCallContext context,
            RequestHandler handler)
            throws A2AError {
        MessageSendParams params = scoped(messageSendParams(decoded.params()), envelope.tenantId());
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

    private static Payload decode(byte[] payload) {
        if (payload == null || payload.length == 0) {
            throw new IllegalArgumentException("PAYLOAD_EMPTY");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(payload);
        } catch (IOException failure) {
            throw new IllegalArgumentException("PAYLOAD_INVALID", failure);
        }
        String method = root.hasNonNull("method") ? root.get("method").asText() : null;
        JsonNode params = root.has("params") ? root.get("params") : root;
        if (params == null || params.isNull()) {
            throw new IllegalArgumentException("PAYLOAD_INVALID");
        }
        Object requestId = root.has("id") && !root.get("id").isNull()
                ? MAPPER.convertValue(root.get("id"), Object.class)
                : null;
        return new Payload(method, params, requestId);
    }

    private static <T> T convert(JsonNode params, Class<T> type) {
        try {
            return JsonUtil.fromJson(params.toString(), type);
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException failure) {
            throw new IllegalArgumentException("PAYLOAD_INVALID", failure);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("PAYLOAD_INVALID", failure);
        }
    }

    private static MessageSendParams messageSendParams(JsonNode params) {
        JsonNode normalized = params.deepCopy();
        JsonNode message = normalized.get("message");
        if (message instanceof ObjectNode messageObject) {
            JsonNode role = messageObject.get("role");
            if (role == null || role.isNull() || role.isTextual() && role.asText().isBlank()) {
                messageObject.put("role", DEFAULT_MESSAGE_ROLE);
            }
        }
        return convert(normalized, MessageSendParams.class);
    }

    private static void requireMethod(Payload payload, String expected) {
        if (payload.method() == null) {
            return;
        }
        if (isMethod(payload, expected)) {
            return;
        }
        throw new InvalidRequestError("A2A method does not match bus event");
    }

    private static boolean isMethod(Payload payload, String expected) {
        return expected.equals(payload.method());
    }

    private static MessageSendParams scoped(MessageSendParams params, String tenantId) {
        requireTenant(params.tenant(), tenantId);
        return new MessageSendParams(params.message(), params.configuration(), params.metadata(), tenantId);
    }

    private static TaskQueryParams scoped(TaskQueryParams params, String tenantId) {
        requireTenant(params.tenant(), tenantId);
        return new TaskQueryParams(params.id(), params.historyLength(), tenantId);
    }

    private static TaskIdParams scoped(TaskIdParams params, String tenantId) {
        requireTenant(params.tenant(), tenantId);
        return new TaskIdParams(params.id(), tenantId);
    }

    private static void requireTenant(String payloadTenant, String envelopeTenant) {
        if (payloadTenant != null && !envelopeTenant.equals(payloadTenant)) {
            throw new IllegalArgumentException("TASK_NOT_FOUND");
        }
    }

    private record Payload(String method, JsonNode params, Object requestId) {
    }
}
