/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer;

import com.openjiuwen.service.bus.consumer.model.Admission;
import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusConsumptionDecision;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;
import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;
import com.openjiuwen.service.bus.consumer.observability.BusConsumerTelemetry;
import com.openjiuwen.service.bus.consumer.port.BusA2aRequestBridge;
import com.openjiuwen.service.bus.consumer.port.BusPayloadResolver;
import com.openjiuwen.service.bus.consumer.port.BusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.port.BusTaskStateProjector;
import com.openjiuwen.service.bus.consumer.port.TaskIdAwareBusA2aRequestBridge;
import com.openjiuwen.service.bus.consumer.runtime.BusConcurrencyGuard;
import com.openjiuwen.service.bus.consumer.stream.StreamReadyProjector;
import com.openjiuwen.service.bus.consumer.validation.BusEnvelopeValidator;

import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.TaskNotFoundError;
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

/**
 * Consumes validated bus events and dispatches them through the standard A2A control plane.
 *
 * @since 2026-07-22
 */
public final class RuntimeBusEventConsumer {
    private static final Logger LOG = LoggerFactory.getLogger(RuntimeBusEventConsumer.class);

    private final BusEnvelopeValidator validator;
    private final BusPayloadResolver payloadResolver;
    private final BusTaskAdmissionStore admissionStore;
    private final BusA2aRequestBridge bridge;
    private final BusTaskProjectionCoordinator projections;
    private final StreamReadyProjector streamReadyProjector;
    private final BusTaskStateProjector taskStateProjector;
    private final BusConsumerTelemetry telemetry;
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
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, BusPayloadResolver resolver,
            BusTaskAdmissionStore admissionStore, BusA2aRequestBridge bridge,
            BusTaskProjectionCoordinator projections) {
        this(validator, resolver, admissionStore, bridge, projections, null, null, new BusConsumerTelemetry(null, null),
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
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, BusPayloadResolver resolver,
            BusTaskAdmissionStore admissionStore, BusA2aRequestBridge bridge, BusTaskProjectionCoordinator projections,
            StreamReadyProjector streamReadyProjector) {
        this(validator, resolver, admissionStore, bridge, projections, streamReadyProjector, null,
                new BusConsumerTelemetry(null, null), new BusConcurrencyGuard(16, 16, 16, 64));
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
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, BusPayloadResolver resolver,
            BusTaskAdmissionStore admissionStore, BusA2aRequestBridge bridge, BusTaskProjectionCoordinator projections,
            StreamReadyProjector streamReadyProjector, BusTaskStateProjector taskStateProjector) {
        this(validator, resolver, admissionStore, bridge, projections, streamReadyProjector, taskStateProjector,
                new BusConsumerTelemetry(null, null), new BusConcurrencyGuard(16, 16, 16, 64));
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
     * @param telemetry
     *            the telemetry value
     */
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, BusPayloadResolver resolver,
            BusTaskAdmissionStore admissionStore, BusA2aRequestBridge bridge, BusTaskProjectionCoordinator projections,
            StreamReadyProjector streamReadyProjector, BusTaskStateProjector taskStateProjector,
            BusConsumerTelemetry telemetry) {
        this(validator, resolver, admissionStore, bridge, projections, streamReadyProjector, taskStateProjector,
                telemetry, new BusConcurrencyGuard(16, 16, 16, 64));
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
     * @param telemetry
     *            the telemetry value
     * @param concurrency
     *            the concurrency value
     */
    public RuntimeBusEventConsumer(BusEnvelopeValidator validator, BusPayloadResolver resolver,
            BusTaskAdmissionStore admissionStore, BusA2aRequestBridge bridge, BusTaskProjectionCoordinator projections,
            StreamReadyProjector streamReadyProjector, BusTaskStateProjector taskStateProjector,
            BusConsumerTelemetry telemetry, BusConcurrencyGuard concurrency) {
        this.validator = validator;
        this.payloadResolver = resolver;
        this.admissionStore = admissionStore;
        this.bridge = bridge;
        this.projections = projections;
        this.streamReadyProjector = streamReadyProjector;
        this.taskStateProjector = taskStateProjector;
        this.telemetry = telemetry;
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
        long started = System.nanoTime();
        String family = envelope == null || envelope.eventType() == null
                ? "unknown"
                : sourceFamily(envelope.eventType());
        BusConsumptionDecision decision = telemetry.observe("bus.consume", family,
                () -> consumeInternal(envelope, brokerPayload));
        telemetry.delivery(family, decision.type().name(), System.nanoTime() - started);
        return decision;
    }

    private BusConsumptionDecision consumeInternal(AgentBusEventEnvelope envelope, byte[] brokerPayload) {
        Optional<String> invalid = validator.validate(envelope);
        if (invalid.isPresent()) {
            return invalidEnvelope(envelope, invalid.get());
        }
        try {
            byte[] payload;
            long payloadStarted = System.nanoTime();
            String payloadMode = envelope.inlinePayload() == null ? "reference" : "inline";
            try {
                payload = envelope.inlinePayload() != null
                        ? envelope.inlinePayload()
                        : telemetry.observe("payload.resolve", sourceFamily(envelope.eventType()), () -> concurrency
                                .call(BusConcurrencyGuard.Lane.PAYLOAD, () -> payloadResolver.resolve(envelope)));
                telemetry.payload(payloadMode, "success", System.nanoTime() - payloadStarted);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                telemetry.payload(payloadMode, "failure", System.nanoTime() - payloadStarted);
                throw failure;
            }
            if (payload == null || payload.length == 0) {
                return failed(envelope, null, "PAYLOAD_EMPTY", false);
            }
            return isCreation(envelope.eventType())
                    ? telemetry.observe("task.admit", sourceFamily(envelope.eventType()),
                            () -> concurrency.admission(envelope.tenantId(), envelope.idempotencyKey(),
                                    () -> consumeCreation(envelope, payload, brokerPayload)))
                    : consumeControl(envelope, payload);
        } catch (BusConcurrencyGuard.BusyException busy) {
            return BusConsumptionDecision.retry(busy.getMessage());
        } catch (TaskNotFoundError missing) {
            return failed(envelope, null, "TASK_NOT_FOUND", false);
        } catch (UnsupportedOperationError unavailable) {
            return failed(envelope, null, "STREAM_NOT_AVAILABLE", false);
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
                telemetry.admission("conflict");
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
            } else if (bridge instanceof TaskIdAwareBusA2aRequestBridge aware) {
                recovered = dispatch(aware, envelope, payload, existing.taskId());
            } else {
                return BusConsumptionDecision.retry("RESERVED_TASK_REQUIRES_ID_AWARE_BRIDGE");
            }
            return admitAndProject(envelope, recovered, existing.taskId(), "REUSED");
        }

        String reservedTaskId = requestedTaskId == null ? stableTaskId(envelope) : requestedTaskId;
        Admission reservation = new Admission(envelope.tenantId(), envelope.idempotencyKey(), digest, reservedTaskId,
                sourceFamily(envelope.eventType()), envelope.correlationId(), envelope.traceId(),
                envelope.sourceServiceId(), envelope.targetServiceId(), envelope.routeHandle(),
                Admission.State.RESERVED);
        Admission reserved = admissionStore.reserve(reservation);
        if (!reserved.requestDigest().equals(digest)) {
            telemetry.admission("conflict");
            return rejected(envelope, "IDEMPOTENCY_KEY_CONFLICT");
        }
        BusDispatchResult result = requestedTaskId == null && bridge instanceof TaskIdAwareBusA2aRequestBridge aware
                ? dispatch(aware, envelope, payload, reserved.taskId())
                : dispatch(envelope, payload);
        return admitAndProject(envelope, result, reserved.taskId(), "CREATED");
    }

    private BusConsumptionDecision admitAndProject(AgentBusEventEnvelope envelope, BusDispatchResult result,
            String reservedTaskId, String idempotencyResult) {
        String taskId = result.taskId();
        if (taskId == null || taskId.isBlank()) {
            if (bridge instanceof TaskIdAwareBusA2aRequestBridge) {
                taskId = reservedTaskId;
            } else {
                return BusConsumptionDecision.retry("A2A_TASK_ID_UNAVAILABLE");
            }
        }
        admissionStore.markAdmitted(envelope.tenantId(), envelope.idempotencyKey(), taskId);
        telemetry.admission(idempotencyResult);
        projectAccepted(envelope, taskId, idempotencyResult);
        if (result.response() != null) {
            projectResponse(envelope, taskId, result);
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
            projectResponse(envelope, result.taskId(), result);
        }
        projectCurrent(envelope.tenantId(), result.taskId());
        return BusConsumptionDecision.consumed();
    }

    private BusConsumptionDecision invalidEnvelope(AgentBusEventEnvelope envelope, String reason) {
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

    private void projectAccepted(AgentBusEventEnvelope envelope, String taskId, String idempotencyResult) {
        String eventType = envelope.eventType().startsWith("A2A") ? "A2A_CALL_ACCEPTED" : "INVOCATION_ACCEPTED";
        projections.project(projection(envelope, taskId,
                new ProjectionFact("ACCEPTED", eventType, 0, Map.of("idempotencyResult", idempotencyResult))));
    }

    private void projectResponse(AgentBusEventEnvelope envelope, String taskId, BusDispatchResult result) {
        String type = envelope.eventType().startsWith("A2A") ? "A2A_CALL_RESPONSE" : "INVOCATION_RESPONSE";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", envelope.eventType());
        if (result.task() != null) {
            data.put("task", result.task());
        }
        if (result.task() == null && result.response() != null) {
            data.put("response", result.response());
        }
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
        return telemetry.observe("a2a.bridge", sourceFamily(envelope.eventType()),
                () -> concurrency.call(BusConcurrencyGuard.Lane.BRIDGE, () -> bridge.handle(envelope, payload)));
    }

    private BusDispatchResult dispatch(TaskIdAwareBusA2aRequestBridge aware, AgentBusEventEnvelope envelope,
            byte[] payload, String taskId) {
        return telemetry.observe("a2a.bridge", sourceFamily(envelope.eventType()),
                () -> concurrency.call(BusConcurrencyGuard.Lane.BRIDGE, () -> aware.handle(envelope, payload, taskId)));
    }

    private record ProjectionFact(String kind, String type, long revision, Map<String, Object> data) {
    }
}
