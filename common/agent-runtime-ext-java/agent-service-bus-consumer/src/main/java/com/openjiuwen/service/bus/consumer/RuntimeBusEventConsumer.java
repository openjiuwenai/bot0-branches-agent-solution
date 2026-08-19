/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.a2a.TaskStoreProjectionPostProcessor;
import com.openjiuwen.service.bus.consumer.model.Admission;
import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusConsumptionDecision;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.runtime.BusConcurrencyGuard;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.stream.StreamReadyProjector;
import com.openjiuwen.service.bus.consumer.validation.BusEnvelopeValidator;

import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.UnsupportedOperationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Consumes validated bus events and dispatches them through the standard A2A control plane.
 *
 * @since 2026-07-22
 */
public final class RuntimeBusEventConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(RuntimeBusEventConsumer.class);

    private final BusEnvelopeValidator validator;
    private final Function<AgentBusEventEnvelope, byte[]> payloadResolver;
    private final InMemoryBusTaskAdmissionStore admissionStore;
    private final RequestHandlerBusA2aBridge bridge;
    private final BusTaskProjectionCoordinator projections;
    private final StreamReadyProjector streamReadyProjector;
    private final TaskStoreProjectionPostProcessor taskStateProjector;
    private final BusConcurrencyGuard concurrency;

    /**
     * Creates a new instance.
     *
     * @param validator
     *            the validator value
     * @param resolver
     *            the resolver value
     * @param admissionStore
     *            the admissionStore value
     * @param bridge
     *            the bridge value
     * @param projections
     *            the projections value
     */
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, Function<AgentBusEventEnvelope, byte[]> resolver,
            InMemoryBusTaskAdmissionStore admissionStore, RequestHandlerBusA2aBridge bridge,
            BusTaskProjectionCoordinator projections) {
        this(validator, resolver, admissionStore, bridge, projections, null, null,
                new BusConcurrencyGuard(16, 16, 16, 64));
    }

    /**
     * Creates a new instance.
     *
     * @param validator
     *            the validator value
     * @param resolver
     *            the resolver value
     * @param admissionStore
     *            the admissionStore value
     * @param bridge
     *            the bridge value
     * @param projections
     *            the projections value
     * @param streamReadyProjector
     *            the streamReadyProjector value
     */
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, Function<AgentBusEventEnvelope, byte[]> resolver,
            InMemoryBusTaskAdmissionStore admissionStore, RequestHandlerBusA2aBridge bridge,
            BusTaskProjectionCoordinator projections, StreamReadyProjector streamReadyProjector) {
        this(validator, resolver, admissionStore, bridge, projections, streamReadyProjector, null,
                new BusConcurrencyGuard(16, 16, 16, 64));
    }

    /**
     * Creates a new instance.
     *
     * @param validator
     *            the validator value
     * @param resolver
     *            the resolver value
     * @param admissionStore
     *            the admissionStore value
     * @param bridge
     *            the bridge value
     * @param projections
     *            the projections value
     * @param streamReadyProjector
     *            the streamReadyProjector value
     * @param taskStateProjector
     *            the taskStateProjector value
     */
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, Function<AgentBusEventEnvelope, byte[]> resolver,
            InMemoryBusTaskAdmissionStore admissionStore, RequestHandlerBusA2aBridge bridge,
            BusTaskProjectionCoordinator projections, StreamReadyProjector streamReadyProjector,
            TaskStoreProjectionPostProcessor taskStateProjector) {
        this(validator, resolver, admissionStore, bridge, projections, streamReadyProjector, taskStateProjector,
                new BusConcurrencyGuard(16, 16, 16, 64));
    }

    /**
     * Creates a new instance.
     *
     * @param validator
     *            the validator value
     * @param resolver
     *            the resolver value
     * @param admissionStore
     *            the admissionStore value
     * @param bridge
     *            the bridge value
     * @param projections
     *            the projections value
     * @param streamReadyProjector
     *            the streamReadyProjector value
     * @param taskStateProjector
     *            the taskStateProjector value
     * @param concurrency
     *            the concurrency value
     */
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, Function<AgentBusEventEnvelope, byte[]> resolver,
            InMemoryBusTaskAdmissionStore admissionStore, RequestHandlerBusA2aBridge bridge,
            BusTaskProjectionCoordinator projections, StreamReadyProjector streamReadyProjector,
            TaskStoreProjectionPostProcessor taskStateProjector, BusConcurrencyGuard concurrency) {
        this.validator = validator;
        this.payloadResolver = resolver;
        this.admissionStore = admissionStore;
        this.bridge = bridge;
        this.projections = projections;
        this.streamReadyProjector = streamReadyProjector;
        this.taskStateProjector = taskStateProjector;
        this.concurrency = concurrency;
    }

    /**
     * Performs the consume operation.
     *
     * @param envelope
     *            the envelope value
     * @param brokerPayload
     *            the brokerPayload value
     *
     * @return the operation result
     */
    public BusConsumptionDecision consume(AgentBusEventEnvelope envelope, byte[] brokerPayload) {
        return consumeInternal(envelope, brokerPayload);
    }

    private BusConsumptionDecision consumeInternal(AgentBusEventEnvelope envelope, byte[] brokerPayload) {
        Optional<String> invalid = validator.validate(envelope);
        if (invalid.isPresent()) {
            return invalidEnvelope(envelope, invalid.get());
        }
        try {
            byte[] payload = envelope.inlinePayload() != null
                    ? envelope.inlinePayload()
                    : concurrency.call(BusConcurrencyGuard.Lane.PAYLOAD, () -> payloadResolver.apply(envelope));
            if (payload == null || payload.length == 0) {
                return failed(envelope, null, "PAYLOAD_EMPTY", false);
            }
            return isCreation(envelope.eventType())
                    ? concurrency.admission(envelope.tenantId(), envelope.idempotencyKey(),
                            () -> consumeCreation(envelope, payload, brokerPayload))
                    : consumeControl(envelope, payload);
        } catch (BusConcurrencyGuard.BusyException busy) {
            return BusConsumptionDecision.retry(busy.getMessage());
        } catch (TaskNotFoundError missing) {
            return failed(envelope, null, "TASK_NOT_FOUND", false);
        } catch (UnsupportedOperationError unavailable) {
            // Terminal / not-subscribable task (e.g. SubscribeToTask on a COMPLETED task). Build the
            // -32004 (UNSUPPORTED_OPERATION) JSON-RPC error ¡ª with the client's request id decoded from
            // the inline payload ¡ª and attach it as the projection's a2aResponse, so the gateway passes
            // it through verbatim, byte-identical to DIRECT (where the runtime's HTTP SubscribeToTask
            // returns -32004 as-is).
            return failedWithResponse(envelope, null, "UNSUPPORTED_OPERATION",
                    bridge.errorResponseJson(A2AErrorCodes.UNSUPPORTED_OPERATION.code(),
                            unavailable.getMessage(), envelope.inlinePayload()),
                    false);
        } catch (IllegalArgumentException invalidPayload) {
            return failed(envelope, null, normalize(invalidPayload.getMessage(), "PAYLOAD_INVALID"), false);
        } catch (A2AError protocolError) {
            return failed(envelope, null, "A2A_ERROR_" + protocolError.getCode(), false);
        } catch (IllegalStateException failure) {
            LOG.warn("Bus event processing failed, messageId={}", envelope.messageId(), failure);
            return BusConsumptionDecision.retry("PROCESSING_FAILED");
        }
    }

    private BusConsumptionDecision consumeCreation(AgentBusEventEnvelope envelope, byte[] payload,
            byte[] brokerPayload) {
        String digest = digestRequest(envelope, brokerPayload == null ? payload : brokerPayload);
        String requestedTaskId = bridge.requestedTaskId(envelope, payload).orElse(null);
        Admission existing = admissionStore.find(envelope.tenantId(), envelope.idempotencyKey()).orElse(null);
        if (existing != null) {
            if (!existing.requestDigest().equals(digest)) {
                return rejected(envelope, "IDEMPOTENCY_KEY_CONFLICT");
            }
            if (existing.state() == Admission.State.ADMITTED) {
                projectAccepted(envelope, existing.taskId(), "REUSED");
                projectCurrent(envelope.tenantId(), existing.taskId());
                return BusConsumptionDecision.consumed();
            }
            BusDispatchResult recovered;
            if (requestedTaskId != null && requestedTaskId.equals(existing.taskId())) {
                recovered = dispatch(envelope, payload);
            } else if (bridge.supportsReservedTaskId()) {
                recovered = dispatch(envelope, payload, existing.taskId());
            } else {
                return BusConsumptionDecision.retry("RESERVED_TASK_REQUIRES_ID_AWARE_BRIDGE");
            }
            return admitAndProject(envelope, payload, recovered, existing.taskId(), "REUSED");
        }

        String reservedTaskId = requestedTaskId == null ? stableTaskId(envelope) : requestedTaskId;
        Admission reservation = new Admission(envelope.tenantId(), envelope.idempotencyKey(), digest, reservedTaskId,
                sourceFamily(envelope.eventType()), envelope.correlationId(), envelope.traceId(),
                envelope.sourceServiceId(), envelope.targetServiceId(), envelope.routeHandle(),
                bridge.requestId(payload), Admission.State.RESERVED);
        Admission reserved = admissionStore.reserve(reservation);
        if (!reserved.requestDigest().equals(digest)) {
            return rejected(envelope, "IDEMPOTENCY_KEY_CONFLICT");
        }
        BusDispatchResult result = requestedTaskId == null && bridge.supportsReservedTaskId()
                ? dispatch(envelope, payload, reserved.taskId())
                : dispatch(envelope, payload);
        return admitAndProject(envelope, payload, result, reserved.taskId(), "CREATED");
    }

    private BusConsumptionDecision admitAndProject(AgentBusEventEnvelope envelope, byte[] payload,
            BusDispatchResult result, String reservedTaskId, String idempotencyResult) {
        String taskId = result.taskId();
        if (taskId == null || taskId.isBlank()) {
            if (bridge.supportsReservedTaskId()) {
                taskId = reservedTaskId;
            } else {
                return BusConsumptionDecision.retry("A2A_TASK_ID_UNAVAILABLE");
            }
        }
        admissionStore.markAdmitted(envelope.tenantId(), envelope.idempotencyKey(), taskId);
        projectAccepted(envelope, taskId, idempotencyResult);
        if (result.response() != null) {
            projectResponse(envelope, taskId, result, payload);
        }
        if (result.streamReady()) {
            projectStreamReady(envelope, taskId);
        }
        projectCurrent(envelope.tenantId(), taskId);
        return BusConsumptionDecision.consumed();
    }

    private BusConsumptionDecision consumeControl(AgentBusEventEnvelope envelope, byte[] payload) {
        BusDispatchResult result = dispatch(envelope, payload);
        if (result.taskId() == null || result.taskId().isBlank()) {
            return failed(envelope, null, "TASK_NOT_FOUND", false);
        }
        if (envelope.eventType().contains("SUBSCRIBE")) {
            projectStreamReady(envelope, result.taskId());
        } else {
            projectResponse(envelope, result.taskId(), result, payload);
        }
        projectCurrent(envelope.tenantId(), result.taskId());
        return BusConsumptionDecision.consumed();
    }

    private BusConsumptionDecision invalidEnvelope(AgentBusEventEnvelope envelope, String reason) {
        if ("TENANT_SCOPE_VIOLATION".equals(reason)) {
            return BusConsumptionDecision.rejected(reason);
        }
        if (!trustedForResponse(envelope)) {
            return BusConsumptionDecision.rejected(reason);
        }
        return switch (reason) {
            case "DEADLINE_EXCEEDED" -> failed(envelope, null, reason, false);
            default -> rejected(envelope, reason);
        };
    }

    private BusConsumptionDecision rejected(AgentBusEventEnvelope envelope, String reason) {
        try {
            projections.project(projection(envelope, null, new ProjectionFact("REJECTED", rejectedType(envelope), 0,
                    Map.of("reason", reason, "retryable", false))));
            return BusConsumptionDecision.rejected(reason);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOG.warn("Failed to persist rejection projection, messageId={}", envelope.messageId(), failure);
            return BusConsumptionDecision.retry("PROJECTION_HANDOFF_FAILED");
        }
    }

    private BusConsumptionDecision failed(AgentBusEventEnvelope envelope, String taskId, String code,
            boolean retryable) {
        if (!trustedForResponse(envelope)) {
            return BusConsumptionDecision.rejected(code);
        }
        try {
            String type = envelope.eventType().startsWith("A2A") ? "A2A_CALL_FAILED" : "INVOCATION_FAILED";
            projections.project(projection(envelope, taskId,
                    new ProjectionFact("FAILED", type, 0, Map.of("errorCode", code, "retryable", retryable))));
            return BusConsumptionDecision.rejected(code);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOG.warn("Failed to persist failure projection, messageId={}", envelope.messageId(), failure);
            return BusConsumptionDecision.retry("PROJECTION_HANDOFF_FAILED");
        }
    }

    /**
     * Emits a FAILED projection that carries a complete a2aResponse JSON-RPC body (e.g. the -32004
     * error built for a terminal / not-subscribable SubscribeToTask), in addition to the errorCode.
     * The gateway's projection feed reads a2aResponse first and passes it through verbatim, so the
     * client sees the runtime's JSON-RPC error ¡ª same as the DIRECT (HTTP) path.
     *
     * @param envelope bus event envelope
     * @param taskId task id (null when not yet bound)
     * @param errorCode stable error code string (e.g. "UNSUPPORTED_OPERATION")
     * @param a2aResponseJson complete A2A JSON-RPC error response body
     * @param retryable whether the error is retryable
     * @return consumption decision
     */
    private BusConsumptionDecision failedWithResponse(AgentBusEventEnvelope envelope, String taskId,
            String errorCode, String a2aResponseJson, boolean retryable) {
        if (!trustedForResponse(envelope)) {
            return BusConsumptionDecision.rejected(errorCode);
        }
        try {
            String type = envelope.eventType().startsWith("A2A") ? "A2A_CALL_FAILED" : "INVOCATION_FAILED";
            projections.project(projection(envelope, taskId, new ProjectionFact("FAILED", type, 0,
                    Map.of("errorCode", errorCode, "a2aResponse", a2aResponseJson, "retryable", retryable))));
            return BusConsumptionDecision.rejected(errorCode);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            LOG.warn("Failed to persist failure projection, messageId={}", envelope.messageId(), failure);
            return BusConsumptionDecision.retry("PROJECTION_HANDOFF_FAILED");
        }
    }

    private void projectAccepted(AgentBusEventEnvelope envelope, String taskId, String idempotencyResult) {
        String eventType = envelope.eventType().startsWith("A2A") ? "A2A_CALL_ACCEPTED" : "INVOCATION_ACCEPTED";
        projections.project(projection(envelope, taskId,
                new ProjectionFact("ACCEPTED", eventType, 0, Map.of("idempotencyResult", idempotencyResult))));
    }

    private void projectResponse(AgentBusEventEnvelope envelope, String taskId, BusDispatchResult result,
            byte[] payload) {
        String type = envelope.eventType().startsWith("A2A") ? "A2A_CALL_RESPONSE" : "INVOCATION_RESPONSE";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", envelope.eventType());
        data.put("a2aResponse", bridge.response(payload, result));
        projections.project(projection(envelope, taskId, new ProjectionFact("RESPONSE", type, 0, Map.copyOf(data))));
    }

    private void projectStreamReady(AgentBusEventEnvelope envelope, String taskId) {
        if (streamReadyProjector == null) {
            throw new IllegalStateException("STREAM_REF_NOT_CONFIGURED");
        }
        String eventType = envelope.eventType().startsWith("A2A") ? "A2A_STREAM_READY" : "INVOCATION_STREAM_READY";
        streamReadyProjector
                .project(projection(envelope, taskId, new ProjectionFact("STREAM_READY", eventType, 0, Map.of())));
    }

    private void projectCurrent(String tenantId, String taskId) {
        if (taskStateProjector != null) {
            taskStateProjector.projectCurrent(tenantId, taskId);
        }
    }

    private static BusResponseProjection projection(AgentBusEventEnvelope envelope, String taskId,
            ProjectionFact fact) {
        return new BusResponseProjection(projectionId(envelope, taskId, fact.kind(), fact.revision()), fact.type(),
                envelope.tenantId(), envelope.correlationId(), taskId, Instant.now(), fact.data(), envelope.traceId(),
                envelope.targetServiceId(), envelope.sourceServiceId(), envelope.routeHandle(),
                envelope.idempotencyKey(), envelope.messageId(), fact.kind(), fact.revision());
    }

    private static boolean isCreation(String type) {
        return "CLIENT_INVOCATION_REQUESTED".equals(type) || "A2A_CALL_REQUESTED".equals(type);
    }

    private static boolean trustedForResponse(AgentBusEventEnvelope envelope) {
        return envelope != null && notBlank(envelope.tenantId()) && notBlank(envelope.sourceServiceId())
                && notBlank(envelope.targetServiceId()) && notBlank(envelope.correlationId())
                && notBlank(envelope.messageId()) && notBlank(envelope.eventType());
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String rejectedType(AgentBusEventEnvelope envelope) {
        return envelope.eventType().startsWith("A2A") ? "A2A_CALL_REJECTED" : "INVOCATION_REJECTED";
    }

    private static String sourceFamily(String type) {
        return type.startsWith("A2A") ? "A2A" : "CLIENT";
    }

    private static String stableTaskId(AgentBusEventEnvelope envelope) {
        return "bus-" + hash(envelope.tenantId(), envelope.idempotencyKey()).substring(0, 32);
    }

    private static String projectionId(AgentBusEventEnvelope envelope, String taskId, String kind, long revision) {
        return hash(envelope.tenantId(), envelope.messageId(), taskId == null ? "none" : taskId, kind,
                Long.toString(revision));
    }

    private static String digestRequest(AgentBusEventEnvelope envelope, byte[] payload) {
        return hash(envelope.eventType(), envelope.tenantId(), envelope.idempotencyKey(),
                HexFormat.of().formatHex(payload));
    }

    private static String hash(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private BusDispatchResult dispatch(AgentBusEventEnvelope envelope, byte[] payload) {
        return concurrency.call(BusConcurrencyGuard.Lane.BRIDGE, () -> bridge.handle(envelope, payload));
    }

    private BusDispatchResult dispatch(AgentBusEventEnvelope envelope, byte[] payload, String taskId) {
        return concurrency.call(BusConcurrencyGuard.Lane.BRIDGE, () -> bridge.handle(envelope, payload, taskId));
    }

    private record ProjectionFact(String kind, String type, long revision, Map<String, Object> data) {
    }
}
