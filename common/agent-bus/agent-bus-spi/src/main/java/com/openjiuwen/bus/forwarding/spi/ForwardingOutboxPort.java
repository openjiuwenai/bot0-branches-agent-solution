/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

/**
 * Sender-side durable-queue port for the C3 outbox substrate.
 *
 * <p>The port abstracts durable outbox storage so the domain model, state machine
 * and harness can ship without a real database; a JDBC / persistent
 * implementation lands once DB / migration ownership is decided (Stage 9 §5).
 * Implementations MUST call {@code ForwardingStateMachine} to validate a
 * transition before persisting, MUST scope every operation by {@code tenantId}
 * (tenant isolation, Rule R-C.c; cross-tenant reads fail explicitly, never fall
 * back), and — from Stage 9 (MI9-001) — MUST guard the DISPATCHING → terminal /
 * retry mutations by {@code leaseOwner}.
 *
 * <p><b>Lease-owner guarded mutation (Stage 9, MI9-001).</b> A record reaches
 * DISPATCHING only via {@link ForwardingOutboxClaimPort#claimDue}, which stamps
 * an exclusive {@link ForwardingLease}. Every outbound transition below carries
 * the caller's {@code leaseOwner}; an implementation rejects the call
 * ({@link ForwardingLeaseException}) when the record is unknown, holds no lease,
 * is leased to a different owner, or is no longer DISPATCHING. A real JDBC
 * adapter encodes this as
 * {@code WHERE tenant_id = ? AND message_id = ? AND lease_owner = ? AND lease_until > now()}.
 *
 * <p><b>Lease lifecycle (Stage 9, MI9-002).</b> ACKED / DLQ / EXPIRED clear the
 * lease (terminal); RETRY_SCHEDULED clears the lease so the next dispatch is
 * gated only by {@code nextAttemptAt}. {@code markDispatching} is intentionally
 * absent — claim / lease is the sole path into DISPATCHING, so a DISPATCHING
 * record always holds a live lease.
 *
 * <p>Authority: {@code ICD-Agent-Bus-Forwarding-Runtime};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.1/§8};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §4/§5}.
 *
 * @since 0.1.0
 */
public interface ForwardingOutboxPort {
    /**
     * Enqueue an envelope into the outbox and return the synchronous ack
     * receipt. A duplicate {@code (tenantId, messageId)} returns an
     * already-accepted receipt without re-enqueueing.
     *
     * <p>{@code sourceServiceId} and {@code targetServiceId} are gateway /
     * discovery audit metadata written onto the resulting
     * {@link ForwardingOutboxRecord} (MI8-002): the source is the calling
     * service instance, the target comes from Stage 3 discovery metadata /
     * gateway audit context — NOT by unpacking {@link ForwardingRouteHandle}
     * (MI9-005) — never a physical endpoint. They live on the record, not on
     * the envelope.
     *
     * @param envelope the forwarding envelope to enqueue
     * @param sourceServiceId the calling service instance identity written onto the record
     * @param targetServiceId the target service identity from discovery metadata
     * @param nowMillisEpoch the enqueue instant, in milliseconds since the epoch
     * @return the synchronous ack receipt (newly enqueued or already-accepted on duplicate)
     */
    ForwardingReceipt enqueue(ForwardingEnvelope envelope, String sourceServiceId,
                              String targetServiceId, long nowMillisEpoch);

    /**
     * Transition a claimed DISPATCHING entry to ACKED (terminal-success).
     * Clears the lease (MI9-002).
     *
     * @param id the message id of the outbox entry
     * @param tenantId the tenant identity scoping the outbox entry
     * @param leaseOwner the lease owner claiming the record
     * @return the resulting outbox status (ACKED)
     * @throws ForwardingLeaseException if the record is unknown, holds no lease,
     *         is leased to a different owner, or is not DISPATCHING
     */
    ForwardingStatus.Outbox markAcked(ForwardingMessageId id, String tenantId, String leaseOwner);

    /**
     * Schedule a retry from a claimed DISPATCHING entry. {@code code} must be
     * retryable (MI9-004); {@code nextAttemptAtMillisEpoch} must be in the
     * future. Clears the lease so the next dispatch is gated by
     * {@code nextAttemptAt} only (MI9-002). Increments {@code attemptCount}.
     *
     * @param id the message id of the outbox entry
     * @param tenantId the tenant identity scoping the outbox entry
     * @param leaseOwner the lease owner claiming the record
     * @param code the retryable failure code
     * @param nextAttemptAtMillisEpoch the instant at which the next dispatch may fire
     * @return the resulting outbox status (RETRY_SCHEDULED)
     * @throws ForwardingLeaseException if the record is unknown, holds no lease,
     *         is leased to a different owner, or is not DISPATCHING
     * @throws IllegalArgumentException if {@code code} is not retryable
     */
    ForwardingStatus.Outbox scheduleRetry(ForwardingMessageId id, String tenantId, String leaseOwner,
                                          ForwardingFailureCode code, long nextAttemptAtMillisEpoch);

    /**
     * Move a claimed DISPATCHING entry to DLQ (terminal). {@code code} may be a
     * non-retryable failure or a retryable code whose retries are exhausted;
     * the dedup outcome is rejected (MI9-004). Clears the lease (MI9-002).
     *
     * @param id the message id of the outbox entry
     * @param tenantId the tenant identity scoping the outbox entry
     * @param leaseOwner the lease owner claiming the record
     * @param code the failure code (non-retryable or exhausted retryable; not a dedup outcome)
     * @return the resulting outbox status (DLQ)
     * @throws ForwardingLeaseException if the record is unknown, holds no lease,
     *         is leased to a different owner, or is not DISPATCHING
     */
    ForwardingStatus.Outbox moveToDlq(ForwardingMessageId id, String tenantId, String leaseOwner,
                                      ForwardingFailureCode code);

    /**
     * Mark a claimed DISPATCHING entry EXPIRED (terminal) — deadline exceeded.
     * Clears the lease (MI9-002).
     *
     * @param id the message id of the outbox entry
     * @param tenantId the tenant identity scoping the outbox entry
     * @param leaseOwner the lease owner claiming the record
     * @return the resulting outbox status (EXPIRED)
     * @throws ForwardingLeaseException if the record is unknown, holds no lease,
     *         is leased to a different owner, or is not DISPATCHING
     */
    ForwardingStatus.Outbox markExpired(ForwardingMessageId id, String tenantId, String leaseOwner);

    /**
     * Current status of an outbox entry (tenant-scoped).
     *
     * @param id the message id of the outbox entry
     * @param tenantId the tenant identity scoping the outbox entry
     * @return the current outbox status
     */
    ForwardingStatus.Outbox statusOf(ForwardingMessageId id, String tenantId);
}
