/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.bus.forwarding.spi;

import java.util.Objects;

/**
 * Ownership lease stamped on a claimed outbox record by
 * {@link ForwardingOutboxClaimPort#claimDue}.
 *
 * <p>C3 persistence (Stage 8) prevents two dispatcher instances from delivering
 * the same outbox record concurrently: {@code claimDue} atomically grants the
 * record to one {@code leaseOwner} until {@code leaseUntilMillisEpoch}. Another
 * owner may reclaim the record only after the lease has expired (the holder
 * crashed or stalled mid-delivery). The lease is a Stage 8 additive field on the
 * outbox record; it carries no payload body, no token stream, no Task execution
 * state, no physical endpoint.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.1};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §4}.
 */
// scope: forwarding substrate — claim ownership metadata only
public record ForwardingLease(String leaseOwner, long leaseUntilMillisEpoch) {

    public ForwardingLease {
        Objects.requireNonNull(leaseOwner, "leaseOwner is required");
        if (leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
    }

    /**
     * Whether this lease is no longer exclusive at the given instant.
     *
     * @param nowMillisEpoch the instant to check, in milliseconds since the epoch
     * @return {@code true} if the lease has expired by the given instant
     */
    public boolean isExpiredAt(long nowMillisEpoch) {
        return leaseUntilMillisEpoch <= nowMillisEpoch;
    }

    /**
     * Whether the given owner is the current holder of this lease.
     *
     * @param owner the owner identity to test
     * @return {@code true} if the given owner holds this lease
     */
    public boolean isHeldBy(String owner) {
        return leaseOwner.equals(owner);
    }
}
