package com.openjiuwen.bus.forwarding.spi;

import java.util.List;

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
     * Extend an active lease the caller already holds. No-op (returns
     * {@code false}) if the record is unknown, tenant-scoped elsewhere, not
     * currently DISPATCHING, or held by a different owner.
     */
    boolean renewLease(ForwardingMessageId id, String tenantId, String leaseOwner,
                       long leaseUntilMillisEpoch);

    /**
     * Release a lease the caller holds (e.g. after a terminal ACK). No-op
     * (returns {@code false}) if the record is unknown, tenant-scoped elsewhere,
     * or held by a different owner.
     */
    boolean releaseLease(ForwardingMessageId id, String tenantId, String leaseOwner);
}
