package com.openjiuwen.bus.forwarding.spi;

/**
 * Raised by a lease-owner guarded outbox mutation when the caller is not the
 * current lease holder of the target record (Stage 9, MI9-001 / MI9-002).
 *
 * <p>Stage 8's {@code claimDue} grants a record to one {@code leaseOwner}; Stage 9
 * makes every subsequent state change ({@code markAcked} / {@code scheduleRetry}
 * / {@code moveToDlq} / {@code markExpired}) carry the caller's
 * {@code leaseOwner} and reject a stale / foreign holder. This prevents a worker
 * whose lease expired (and was reclaimed by another worker) from mutating a
 * record it no longer owns. A real JDBC adapter encodes the same guard as
 * {@code WHERE tenant_id = ? AND message_id = ? AND lease_owner = ? AND lease_until > now()}.
 *
 * <p>{@link Reason} distinguishes the failure mode so the harness can assert the
 * exact guard that tripped. The worker treats any
 * {@code ForwardingLeaseException} as "skip this record"; the record's true owner
 * (or the next reclaim) drives it forward.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §4/§5};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.1}.
 */
public class ForwardingLeaseException extends IllegalStateException {

    /** Why a lease-owner guarded mutation was rejected. */
    public enum Reason {
        /** No outbox record exists for {@code (tenantId, messageId)}. */
        RECORD_NOT_FOUND,
        /** The record has no active lease (never claimed or already released). */
        NO_LEASE,
        /** The record is leased, but to a different owner (stale / foreign holder). */
        OWNER_MISMATCH,
        /** The record is not DISPATCHING (already terminal or wrong state). */
        NOT_DISPATCHING
    }

    private final Reason reason;

    public ForwardingLeaseException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** The specific guard that tripped. */
    public Reason reason() {
        return reason;
    }
}
