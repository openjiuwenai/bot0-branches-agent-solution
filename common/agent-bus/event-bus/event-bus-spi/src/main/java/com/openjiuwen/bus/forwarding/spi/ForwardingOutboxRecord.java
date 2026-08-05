/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

import java.util.Objects;

/**
 * Sender-side durable-queue record for the C3 outbox substrate (Stage 8 / 9).
 *
 * <p>Mirrors the outbox record schema of {@code ICD-Agent-Bus-Forwarding-Runtime}
 * field-for-field: the gateway writes a record when it accepts an envelope
 * ({@link ForwardingOutboxPort#enqueue}), and the dispatcher worker reads /
 * claims / mutates it. The record is the single source of truth a real JDBC
 * adapter must persist — it carries exactly the ICD fields plus the
 * Stage 8 additive {@link ForwardingLease} (claim / lease ownership).
 *
 * <p>{@code sourceServiceId} and {@code targetServiceId} live on the record, not
 * on {@link ForwardingEnvelope} (MI8-002). {@code targetServiceId} comes from
 * Stage 3 discovery metadata / gateway audit context — NOT by unpacking the
 * opaque {@code routeHandle} (MI9-005).
 *
 * <p>Stage 9 (MI9-003)固化 the runtime ICD condition-field rules in the compact
 * constructor: tenant continuity, {@code attemptCount >= 0}, and the per-status
 * lease / failure-code / next-attempt invariants (see
 * {@link #validateStatusInvariants}). A future edit that weakens any of these
 * fails the harness.
 *
 * <p>Forbidden-payload invariant (HD4): this record NEVER carries a payload body,
 * a token stream, Task execution state, or a physical endpoint. There are no
 * such fields, by design; large payloads take the {@code payloadRef} data
 * reference path.
 *
 * <p>Authority: {@code ICD-Agent-Bus-Forwarding-Runtime} (outbox record fields);
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.1};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §3}.
 */
// scope: forwarding substrate — durable outbox record; never a payload body
public record ForwardingOutboxRecord(
        String tenantId,
        ForwardingMessageId messageId,
        String sourceServiceId,
        String targetServiceId,
        ForwardingRouteHandle routeHandle,
        String payloadRef,
        ForwardingStatus.Outbox status,
        int attemptCount,
        long nextAttemptAtMillisEpoch,
        long createdAtMillisEpoch,
        long updatedAtMillisEpoch,
        ForwardingFailureCode lastFailureCode,
        ForwardingLease lease,
        // FEAT-013 cross-hop correlation key (mirrored from ForwardingEnvelope.correlationId at enqueue,
        // like sourceServiceId/targetServiceId per L2 §2.3.1). Nullable: JDBC-loaded rows pass null (no V3
        // DDL for W2 — a real correlation_id column is deferred until FEAT-013 wires JDBC); non-null when
        // produced from an envelope via the in-memory outbox. The gateway (S2) matches responses by this field.
        String correlationId,
        // FEAT-013/014 event-type discriminator (mirrored from ForwardingEnvelope.eventType at enqueue).
        // Nullable: JDBC-loaded rows pass null; non-null when produced from an envelope. The gateway (S2)
        // classifies responses by this field (L2 §4.2), avoiding descriptor-encoding coupling.
        AgentBusEventType eventType,
        // FEAT-013/014 control plane (P-06): mirrored from ForwardingEnvelope at enqueue so the relay can
        // publish them as first-class broker headers — they no longer overload payloadRef. Nullable
        // (JDBC-back-compat rows / control-only); present on data-bearing request/response records.
        // deadlineMillisEpoch uses Long.MAX_VALUE for "no deadline".
        String traceId,
        String idempotencyKey,
        String capability,
        long deadlineMillisEpoch,
        String inlinePayload,
        // FEAT-013/014 originalCaller (P-06, L2 feat-014 §4): the original gateway/caller serviceId,
        // mirrored from ForwardingEnvelope at enqueue so the relay can publish it as a first-class header
        // — the runtime routes the response back to it across the relay hop. Routing/control, not A2A data.
        String originalCaller
) {
    public ForwardingOutboxRecord {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Objects.requireNonNull(messageId, "messageId is required");
        requireNonBlank(sourceServiceId, "sourceServiceId");
        requireNonBlank(targetServiceId, "targetServiceId");
        Objects.requireNonNull(routeHandle, "routeHandle is required");
        Objects.requireNonNull(status, "status is required");
        // payloadRef is the A2A data reference (P-06): null (control-only) or non-blank — NEVER a
        // control-descriptor token (control rides the first-class fields below).
        if (payloadRef != null && payloadRef.isBlank()) {
            throw new IllegalArgumentException("payloadRef must be null or non-blank");
        }
        if (!tenantId.equals(routeHandle.tenantScope())) {
            throw new IllegalArgumentException(
                    "tenant_mismatch: outbox tenantId '" + tenantId
                    + "' must equal routeHandle tenantScope '" + routeHandle.tenantScope() + "'");
        }
        if (attemptCount < 0) {
            throw new IllegalArgumentException("attemptCount must be >= 0");
        }
        validateStatusInvariants(status, nextAttemptAtMillisEpoch, lastFailureCode, lease);
        // correlationId: null (JDBC back-compat / control-only) or non-blank (FEAT-013 correlation).
        if (correlationId != null && correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId must be null or non-blank");
        }
        // control-plane + inlinePayload: null (control-only / back-compat) or non-blank — blank is an error.
        requireNullOrNonBlank(traceId, "traceId");
        requireNullOrNonBlank(idempotencyKey, "idempotencyKey");
        requireNullOrNonBlank(capability, "capability");
        requireNullOrNonBlank(inlinePayload, "inlinePayload");
        requireNullOrNonBlank(originalCaller, "originalCaller");
    }

    /**
     * Back-compat canonical constructor (pre-P-06 15-arg form). The control-plane + inlinePayload
     * fields default to absent (null / no-deadline = {@code Long.MAX_VALUE}). Lets pre-P-06 call
     * sites compile unchanged; new data-bearing code uses the full constructor so control + data
     * each ride their own first-class fields. {@code routeHandle} stays on the record (typed).
     *
     * @param tenantId                tenant scope
     * @param messageId               outbox message id
     * @param sourceServiceId         source service id
     * @param targetServiceId         target service id
     * @param routeHandle             opaque typed route handle
     * @param payloadRef              A2A data reference (null for control-only)
     * @param status                   outbox status
     * @param attemptCount            dispatch attempt count
     * @param nextAttemptAtMillisEpoch scheduled next attempt (epoch millis)
     * @param createdAtMillisEpoch    creation instant
     * @param updatedAtMillisEpoch    last-update instant
     * @param lastFailureCode          last failure code (nullable)
     * @param lease                    current lease (nullable)
     * @param correlationId            cross-hop correlation key (nullable)
     * @param eventType                event-type discriminator (nullable)
     */
    public ForwardingOutboxRecord(String tenantId, ForwardingMessageId messageId, String sourceServiceId,
                                  String targetServiceId, ForwardingRouteHandle routeHandle, String payloadRef,
                                  ForwardingStatus.Outbox status, int attemptCount, long nextAttemptAtMillisEpoch,
                                  long createdAtMillisEpoch, long updatedAtMillisEpoch,
                                  ForwardingFailureCode lastFailureCode, ForwardingLease lease,
                                  String correlationId, AgentBusEventType eventType) {
        this(tenantId, messageId, sourceServiceId, targetServiceId, routeHandle, payloadRef, status,
                attemptCount, nextAttemptAtMillisEpoch, createdAtMillisEpoch, updatedAtMillisEpoch,
                lastFailureCode, lease, correlationId, eventType,
                null, null, null, Long.MAX_VALUE, null, null);
    }

    /**
     * Per-status condition-field invariants (MI9-003). Extracted so the
     * constructor stays readable; exercised directly by the harness.
     *
     * @param status the outbox status to validate
     * @param nextAttemptAtMillisEpoch the scheduled next-attempt instant (epoch millis)
     * @param lastFailureCode the last failure code recorded on the outbox row
     * @param lease the current lease held on the outbox row, or null if unleased
     */
    static void validateStatusInvariants(ForwardingStatus.Outbox status,
                                         long nextAttemptAtMillisEpoch,
                                         ForwardingFailureCode lastFailureCode,
                                         ForwardingLease lease) {
        switch (status) {
            case PENDING -> requireCondition(true, "fresh entry: no extra invariants");
            case DISPATCHING -> requireCondition(lease != null,
                    "DISPATCHING outbox record must hold a non-null lease (lease-safe, MI9-002)");
            case RETRY_SCHEDULED -> {
                requireCondition(nextAttemptAtMillisEpoch > 0,
                        "RETRY_SCHEDULED outbox record requires nextAttemptAtMillisEpoch > 0");
                requireCondition(lastFailureCode != null && lastFailureCode.retryable(),
                        "RETRY_SCHEDULED outbox record requires a retryable lastFailureCode (MI9-004)");
                requireCondition(lease == null,
                        "RETRY_SCHEDULED outbox record must not hold a lease (MI9-002)");
            }
            case ACKED -> {
                requireCondition(lastFailureCode == null,
                        "ACKED outbox record must not carry a lastFailureCode");
                requireCondition(lease == null,
                        "ACKED outbox record must not hold a lease (terminal, MI9-002)");
            }
            case DLQ -> {
                requireCondition(lastFailureCode != null,
                        "DLQ outbox record requires a lastFailureCode");
                requireCondition(lease == null,
                        "DLQ outbox record must not hold a lease (terminal, MI9-002)");
            }
            case EXPIRED -> {
                requireCondition(lastFailureCode != null,
                        "EXPIRED outbox record requires a lastFailureCode");
                requireCondition(lease == null,
                        "EXPIRED outbox record must not hold a lease (terminal, MI9-002)");
            }
        }
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireNullOrNonBlank(String value, String name) {
        // null = absent (control-only / back-compat); a blank value is a wiring error, not a valid absence.
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must be null or non-blank");
        }
    }

    private static void requireCondition(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Whether this record currently holds an unexpired lease at the given instant.
     *
     * @param nowMillisEpoch the instant to test, in epoch milliseconds
     * @return true if the record holds a non-null, unexpired lease at the given instant
     */
    public boolean isActivelyLeasedAt(long nowMillisEpoch) {
        return lease != null && !lease.isExpiredAt(nowMillisEpoch);
    }

    /**
     * Whether this record is in a terminal state (no further dispatch).
     *
     * @return true if the status is ACKED, DLQ, or EXPIRED
     */
    public boolean isTerminal() {
        return status == ForwardingStatus.Outbox.ACKED
                || status == ForwardingStatus.Outbox.DLQ
                || status == ForwardingStatus.Outbox.EXPIRED;
    }
}
