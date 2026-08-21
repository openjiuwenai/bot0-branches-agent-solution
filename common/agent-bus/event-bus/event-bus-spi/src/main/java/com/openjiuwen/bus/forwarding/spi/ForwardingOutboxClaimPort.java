/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

import java.util.List;
import java.util.Optional;

/**
 * Concurrent claim / lease port for the C3 outbox substrate (Stage 8, MI8-001).
 *
 * <p>Replaces the naive {@code findRetryable(now)} projection: real persistence
 * must prevent two dispatcher instances from delivering the same outbox record
 * concurrently, so due messages are not merely <em>queried</em> — they are
 * <em>claimed</em>. {@link #claimDue} atomically selects due, tenant-scoped,
 * non-terminal records whose lease is free or expired, transitions each to
 * {@link ForwardingStatus.Outbox#DISPATCHING}, stamps an exclusive
 * {@link ForwardingLease} owned by the caller, and returns the claimed records.
 *
 * <p>Claim / lease invariants (Stage 8 plan §3 slice 3):
 * <ul>
 *   <li>claim is tenant-scoped; a cross-tenant claim returns nothing (Rule R-C.c).</li>
 *   <li>one outbox record is held by at most one lease owner at a time.</li>
 *   <li>an expired lease may be reclaimed by another owner.</li>
 *   <li>only records with {@code nextAttemptAt <= now} (or a fresh PENDING) are due.</li>
 *   <li>terminal records (ACKED / DLQ / EXPIRED) are never claimable.</li>
 *   <li>claim + the PENDING/RETRY_SCHEDULED → DISPATCHING transition happen in one
 *       atomic step (single transaction / CAS in a real JDBC adapter).</li>
 * </ul>
 *
 * <p>The port never bypasses {@link ForwardingRouteHandle} to a physical endpoint
 * and never writes Task execution state. A real JDBC implementation is deferred
 * (decision §6.1); Stage 8 ships this interface plus an in-memory lease harness.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.1/§8};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §4}.
 *
 * @since 0.1.0
 */
public interface ForwardingOutboxClaimPort {
    /**
     * Atomically claim up to {@code limit} due outbox records for {@code tenantId},
     * granting each to {@code leaseOwner} until {@code leaseUntilMillisEpoch}.
     *
     * <p>A record is due if it is fresh {@code PENDING}, a {@code RETRY_SCHEDULED}
     * record whose {@code nextAttemptAt <= now}, or a {@code DISPATCHING} record
     * whose previous lease has expired (stuck holder reclaim). Terminal records
     * and records under an active lease are skipped. Each claimed record is
     * transitioned to {@code DISPATCHING} (fresh / retry) or re-leased in place
     * (reclaim) and stamped with the new lease.
     *
     * @param tenantId             tenant scope of the claim (Rule R-C.c)
     * @param nowMillisEpoch       the claim instant; used to judge due / lease expiry
     * @param limit                max records to claim in this call ({@code > 0})
     * @param leaseOwner           identity of the claiming dispatcher instance
     * @param leaseUntilMillisEpoch instant until which the claim is exclusive
     *                              ({@code > nowMillisEpoch})
     * @return the claimed records (already DISPATCHING, leased to the caller)
     */
    List<ForwardingOutboxRecord> claimDue(String tenantId, long nowMillisEpoch, int limit,
                                          String leaseOwner, long leaseUntilMillisEpoch);

    /**
     * Atomically claim ONE specific outbox record by {@code id}, granting it to
     * {@code leaseOwner} until {@code leaseUntilMillisEpoch}.
     *
     * <p>Identical due-conditions to {@link #claimDue(String, long, int, String, long)} — fresh
     * {@code PENDING}, a {@code RETRY_SCHEDULED} record whose {@code nextAttemptAt <= now}, or a
     * {@code DISPATCHING} record whose previous lease has expired (stuck-holder reclaim) — but
     * restricted to the single named record. This is the <em>targeted</em> claim: the
     * enqueue-then-immediately-produce path (the relay's re-publish, the runtime's response
     * produce) claims EXACTLY the record it just enqueued (locating it via the
     * {@link ForwardingReceipt#messageId() enqueue receipt}), so on a shared outbox it can never
     * grab another role's naked record and re-publish it through the caller's own producer
     * (the wrong-topic "shift chain", defect A). Terminal records (ACKED / DLQ / EXPIRED), records
     * under another owner's <em>active</em> lease, and unknown ids return empty — the caller falls
     * back to its crash-recovery / redeliver path.
     *
     * <p><b>HA / multi-instance.</b> The reclaim path re-leases a prior crashed attempt's expired
     * {@code DISPATCHING} row, so an at-least-once redelivery re-claims the record a dead instance
     * left stuck; any instance may reclaim an expired lease (the due-condition ignores the prior
     * owner). A live lease held by another instance is NOT stolen (the {@code DISPATCHING AND
     * lease_until <= now} clause is false while that lease is live) — the claim returns empty, and
     * the caller rejects-for-redeliver.
     *
     * @param id                   the message id of the record to claim
     * @param tenantId             tenant scope of the record (Rule R-C.c)
     * @param nowMillisEpoch       the claim instant; used to judge due / lease expiry
     * @param leaseOwner           identity of the claiming dispatcher instance
     * @param leaseUntilMillisEpoch instant until which the claim is exclusive
     *                              ({@code > nowMillisEpoch})
     * @return the claimed record (already DISPATCHING / re-leased, leased to the caller),
     *         or empty if the record is unknown / terminal / under another owner's active lease
     */
    Optional<ForwardingOutboxRecord> claim(ForwardingMessageId id, String tenantId, long nowMillisEpoch,
                                           String leaseOwner, long leaseUntilMillisEpoch);

    /**
     * Atomically claim up to {@code limit} due outbox records for {@code tenantId} whose
     * {@code sourceServiceId} equals the given value, granting each to {@code leaseOwner} until
     * {@code leaseUntilMillisEpoch}.
     *
     * <p>Source-scoped variant of {@link #claimDue(String, long, int, String, long)} for a batch
     * sweeper that shares the outbox table with other roles (e.g. the gateway outbox dispatcher
     * on a shared {@code agent_bus_forwarding_outbox}). Restricting the claim to the caller's own
     * source means the sweeper never claims — and therefore never has to release — another role's
     * records: the cross-role claim race (defect A) and the release-then-strand residue
     * ({@code lease_owner} left stamped on a foreign row) both disappear. Same due-conditions,
     * {@code DISPATCHING} transition, lease stamping, and {@code FOR UPDATE SKIP LOCKED}
     * concurrency as the source-agnostic overload.
     *
     * <p><b>HA / multi-instance.</b> Instances of the same role share {@code sourceServiceId} and
     * compete for the same scoped rows via the {@code SKIP LOCKED} claim — each row is claimed by
     * at most one instance per tick.
     *
     * @param request the source-scoped claim request (tenant, now, limit, lease owner,
     *                lease expiry, source service)
     * @return the claimed records (already DISPATCHING, leased to the caller)
     */
    List<ForwardingOutboxRecord> claimDue(ClaimDueRequest request);

    /**
     * Extend an active lease the caller already holds. No-op (returns
     * {@code false}) if the record is unknown, tenant-scoped elsewhere, not
     * currently DISPATCHING, or held by a different owner.
     *
     * @param id                   the message id of the record whose lease is renewed
     * @param tenantId             tenant scope of the record
     * @param leaseOwner           identity of the claiming dispatcher instance holding the lease
     * @param leaseUntilMillisEpoch new instant until which the lease is extended ({@code > nowMillisEpoch})
     * @return {@code true} if the lease was extended; {@code false} if the record is unknown,
     *         cross-tenant, not DISPATCHING, or held by a different owner
     */
    boolean renewLease(ForwardingMessageId id, String tenantId, String leaseOwner,
                       long leaseUntilMillisEpoch);

    /**
     * Release a lease the caller holds (e.g. after a terminal ACK). No-op
     * (returns {@code false}) if the record is unknown, tenant-scoped elsewhere,
     * or held by a different owner.
     *
     * @param id         the message id of the record whose lease is released
     * @param tenantId   tenant scope of the record
     * @param leaseOwner identity of the claiming dispatcher instance holding the lease
     * @return {@code true} if the lease was released; {@code false} if the record is unknown,
     *         cross-tenant, or held by a different owner
     */
    boolean releaseLease(ForwardingMessageId id, String tenantId, String leaseOwner);
}
