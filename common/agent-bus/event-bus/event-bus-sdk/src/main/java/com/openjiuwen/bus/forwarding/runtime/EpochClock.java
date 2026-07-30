/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime;

/**
 * Wall-clock source for the dispatch worker (Stage 11, MI11-001).
 *
 * <p>Lets {@link ForwardingDispatcherWorker} judge lease expiry against real
 * elapsed time rather than the tick-start instant, so lease renewal fires when a
 * long tick approaches the lease TTL. Before Stage 11 the worker computed the
 * remaining lease TTL as {@code leaseUntil - tickNow}, where both operands were
 * the {@code runOnce} tick-start instant — so the remainder never shrank inside
 * a tick and renewal could never fire under a natural {@link ForwardingDispatchLoop}
 * driver (each tick stamps {@code leaseUntil = now + leaseDurationMillis}). This
 * port is the fix: the worker reads the live clock for the renewal check and the
 * delivery instant, while {@code claimDue} still uses the tick-start instant as
 * the claim moment.
 *
 * <p>The worker holds no scheduler, no thread, no transport; this port is its
 * only time dependency, injected (default {@link #SYSTEM}) so tests can control
 * elapsed time deterministically. It is a plain JDK-portable type — no Spring,
 * JDBC, broker or scheduler dependency.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §5}.
 *
 * @since 0.1.0
 */
@FunctionalInterface
public interface EpochClock {
    /** Default source: {@link System#currentTimeMillis()}. */
    EpochClock SYSTEM = System::currentTimeMillis;

    /**
     * Get the current time as epoch milliseconds.
     *
     * @return the current time as epoch milliseconds (same basis as the
     *         {@code nowMillisEpoch} / {@code leaseUntilMillisEpoch} arguments)
     */
    long epochMillis();
}
