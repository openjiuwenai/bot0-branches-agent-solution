/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Minimal dispatch loop that drives {@link ForwardingDispatcherWorker#runOnce}
 * ticks from an injected {@link TickSource} (Stage 10, MI10-004). This skeleton
 * makes the dispatch scheduling responsibility explicit: the loop owns no
 * clock, no scheduler, no thread pool, no transport. The caller injects (a) when
 * to run the next tick ({@link TickSource}) and (b) what to do when a tick finds
 * no due records ({@link IdleStrategy}). Real polling cadence, threading, idle
 * backoff and concurrent worker sharding are the caller's concern, kept out of
 * {@code agent-bus} (decision §6.1: no scheduler / timer dependency in the bus).
 *
 * <p>The loop is the bridge between {@code ForwardingDispatcherWorker} (a single
 * synchronous tick) and whatever drives production dispatch — a fixed-rate
 * executor, a scheduler bean, a reactive pulse, a manual test. Because the clock
 * and the trigger are injected, the loop is fully deterministic and unit-testable
 * without any thread or sleep.
 *
 * <p>Each tick's {@code leaseUntilMillisEpoch} is derived as the tick instant plus
 * {@code leaseDurationMillis}; per-tick lease renewal inside the tick is still the
 * worker's job (via its {@link ForwardingDispatcherWorker.DispatchLeasePolicy},
 * MI10-002). The loop never delivers, never persists, never writes Task state.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §5};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §5}.
 *
 * @since 0.1.0
 */
public final class ForwardingDispatchLoop {
    /** No-op idle strategy: a busy loop that never backs off. For tests / tight loops. */
    public static final IdleStrategy NO_BACKOFF = tick -> {};

    private final ForwardingDispatcherWorker worker;
    private final TickSource tickSource;
    private final IdleStrategy idleStrategy;

    public ForwardingDispatchLoop(ForwardingDispatcherWorker worker, TickSource tickSource,
                                  IdleStrategy idleStrategy) {
        this.worker = Objects.requireNonNull(worker, "worker is required");
        this.tickSource = Objects.requireNonNull(tickSource, "tickSource is required");
        this.idleStrategy = Objects.requireNonNull(idleStrategy, "idleStrategy is required");
    }

    /**
     * Supplies the instant of the next tick, or empty to stop the loop. Injected
     * so the loop holds no clock of its own (deterministic / testable).
     */
    @FunctionalInterface
    public interface TickSource {
        /**
         * Returns the next tick instant, or empty to signal the loop should stop.
         *
         * @return the next tick instant, or empty to signal the loop should stop
         */
        OptionalLong nextTickMillisEpoch();
    }

    /**
     * Reacts to an idle tick — one that claimed no records. The implementation
     * may back off (sleep / yield / skip) or do nothing; the loop itself never
     * sleeps. Injected so backoff strategy stays outside the bus.
     */
    @FunctionalInterface
    public interface IdleStrategy {
        /**
         * React to an idle tick — one that claimed no records (the implementation
         * may back off or do nothing).
         *
         * @param lastTick the result of the idle tick
         */
        void onIdle(ForwardingDispatcherWorker.DispatchTickResult lastTick);
    }

    /**
     * Run ticks until {@link TickSource} stops, aggregating the results.
     *
     * <p>For every instant returned by the source the loop runs one worker tick;
     * a tick that claims nothing triggers {@link IdleStrategy#onIdle}. The
     * returned {@link ForwardingDispatcherWorker.DispatchTickResult} aggregates
     * every tick run (its counts satisfy the same self-consistency invariant as
     * a single tick).
     *
     * @param tenantId            tenant scope of the loop (Rule R-C.c); applied to every tick
     * @param limit               max records to claim per tick ({@code > 0})
     * @param leaseOwner          identity of this worker / loop instance
     * @param leaseDurationMillis lease TTL applied to each tick ({@code > 0});
     *                            each tick's leaseUntil = its instant + this duration
     * @return the aggregate of all ticks run
     */
    public ForwardingDispatcherWorker.DispatchTickResult run(String tenantId, int limit,
                                                             String leaseOwner,
                                                             long leaseDurationMillis) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        Objects.requireNonNull(leaseOwner, "leaseOwner is required");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        if (leaseDurationMillis <= 0) {
            throw new IllegalArgumentException("leaseDurationMillis must be > 0");
        }

        int claimed = 0;
        int acked = 0;
        int retried = 0;
        int dlqd = 0;
        int expired = 0;
        int skipped = 0;
        while (true) {
            OptionalLong next = tickSource.nextTickMillisEpoch();
            if (next.isEmpty()) {
                break;
            }
            long now = next.getAsLong();
            ForwardingDispatcherWorker.DispatchTickResult tick =
                    worker.runOnce(tenantId, now, limit, leaseOwner, now + leaseDurationMillis);
            claimed += tick.claimed();
            acked += tick.acked();
            retried += tick.retried();
            dlqd += tick.dlqd();
            expired += tick.expired();
            skipped += tick.skipped();
            if (tick.claimed() == 0) {
                idleStrategy.onIdle(tick);
            }
        }
        return new ForwardingDispatcherWorker.DispatchTickResult(
                claimed, acked, retried, dlqd, expired, skipped);
    }
}
