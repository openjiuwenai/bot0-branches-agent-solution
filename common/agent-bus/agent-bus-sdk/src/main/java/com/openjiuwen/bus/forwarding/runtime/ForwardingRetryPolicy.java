/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.bus.forwarding.runtime;

import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Retry / backoff policy for the C3 forwarding dispatcher (Stage 14).
 *
 * <p><b>Separation of concerns (Stage 14).</b> Before Stage 14 a retryable
 * delivery failure carried its own {@code nextAttemptAt} on the
 * {@link com.openjiuwen.bus.forwarding.spi.ForwardingDeliveryResult} — the
 * delivery binding decided <em>when</em> to retry. That conflates two concerns:
 * the <em>delivery action</em> (did it reach the receiver?) and <em>retry
 * governance</em> (when, if ever, do we try again, and when do we give up?). A
 * real transport binding should report a retryable failure and know nothing
 * about backoff; a retry policy owns the timing. This port is where backoff now
 * lives, injected into {@link ForwardingDispatcherWorker} exactly like
 * {@link ForwardingDispatcherWorker.DispatchLeasePolicy} and {@link EpochClock}.
 *
 * <p>The worker, on a {@code RETRY_SCHEDULED} result, asks the policy two
 * questions and routes accordingly:
 * <ul>
 *   <li>{@link #exhausted} — if retries are spent, the record goes to DLQ (not
 *       RETRY); a retryable failure code whose retries are exhausted is a legal
 *       DLQ code.</li>
 *   <li>otherwise {@link #nextAttemptAt} — the instant the record becomes due
 *       again, written to the outbox {@code nextAttemptAt} and gated on by the
 *       next {@code claimDue}.</li>
 * </ul>
 *
 * <p>This is a plain JDK-portable type — no Spring, JDBC, broker or scheduler
 * dependency (forwarding purity, decision §6.1). It holds no per-route state;
 * a {@link ForwardingCircuitBreaker} is the (deferred) seam for per-route
 * failure-rate governance.
 *
 * <p><b>Independent of the transport decision.</b> Stage 13 left the push / pull /
 * MQ model undecided (T1-T4, H2/H3). Retry timing does not depend on it: a
 * retryable failure schedules a later attempt regardless of whether the next
 * attempt is pushed by the dispatcher or pulled by the receiver. This port
 * therefore ships now, ahead of the transport ruling, so the backoff math is
 * testable in isolation.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §5};
 * {@code docs/architecture/l0/10-governance/review-packets/agent-bus-forwarding-runtime-transport-candidates.md}
 * (deliver-retry-policy-subitem).
 *
 * @since 0.1.0
 */
public interface ForwardingRetryPolicy {
    /**
     * Default exponential-backoff policy: {@code base 100ms}, {@code cap 60s},
     * {@code maxAttempts 5} (so a message is attempted once then retried up to
     * five times), no jitter. Production may construct an
     * {@link ExponentialBackoff} with a jitter source; tests inject a fixed
     * jitter for determinism.
     */
    ForwardingRetryPolicy DEFAULT =
            new ExponentialBackoff(100L, 60_000L, 5, () -> 0L);

    /**
     * The instant at which a retryable failure should next become due, given the
     * record's current {@code attemptCount} (retries already recorded; {@code 0}
     * on the first failure) and the delivery instant {@code nowMillisEpoch}.
     *
     * <p>The returned value is on the same epoch-millis basis as the
     * {@code nowMillisEpoch} / {@code leaseUntilMillisEpoch} arguments, and is
     * written verbatim onto the outbox {@code nextAttemptAt} by
     * {@link com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort#scheduleRetry}.
     *
     * <p>{@code code} is the retryable failure code. The default implementation's
     * backoff depends only on {@code attemptCount}; the {@code code} argument is
     * retained so a future per-code policy (e.g. a longer backoff for
     * {@link ForwardingFailureCode#BACKPRESSURE_REJECTED}) needs no signature
     * change.
     *
     * @param code           the retryable failure code (already classified
     *                       retryable by the delivery result)
     * @param attemptCount   retries already recorded ({@code >= 0}; {@code 0} on
     *                       the first failure)
     * @param nowMillisEpoch the delivery instant (epoch millis)
     * @return the next-attempt instant ({@code > nowMillisEpoch})
     */
    long nextAttemptAt(ForwardingFailureCode code, int attemptCount, long nowMillisEpoch);

    /**
     * Whether retries are exhausted at the given {@code attemptCount} (retries
     * already recorded). When {@code true} the worker routes the record to DLQ
     * instead of scheduling another RETRY. {@code attemptCount == 0} on the first
     * failure.
     *
     * @param attemptCount retries already recorded ({@code >= 0})
     * @return {@code true} if retries are exhausted and the record should go to DLQ
     */
    boolean exhausted(int attemptCount);

    /**
     * Exponential-backoff implementation: {@code delay = min(cap, base <<
     * attempt) + jitter}, and retries are exhausted once
     * {@code attemptCount >= maxAttempts}.
     *
     * <p><b>Overflow-safe.</b> The shift is capped so {@code base << shift}
     * cannot overflow to a non-positive value: {@code shift <= 62 - (highest set
     * bit of base)}, which keeps the shifted value positive. A fixed cap of 62 is
     * wrong for a multi-bit base — {@code 100 << 62} shifts every bit out of the
     * 64-bit {@code long} and yields {@code 0} (not a negative), slipping past a
     * sign check. Any value at or above {@code capMillis} is clamped to it, so a
     * runaway {@code attemptCount} never produces a negative, zero or absurd
     * delay. No {@link Math#pow} (which would overflow silently on a
     * {@code double}→{@code long} coercion).
     *
     * <p><b>Testable jitter.</b> Jitter is a {@link LongSupplier} so tests pass a
     * fixed value and observe the exact {@code now + delay + jitter}; production
     * passes a bounded random source. A negative jitter is clamped to {@code 0}.
     *
     * <p>Immutable value record; the canonical constructor validates the
     * invariants so a misconfigured policy fails at construction, not at the
     * first retry.
     */
    record ExponentialBackoff(long baseMillis, long capMillis, int maxAttempts,
            LongSupplier jitterMillis) implements ForwardingRetryPolicy {

        /**
         * Validates the invariants of the backoff policy components so a
         * misconfigured policy fails at construction, not at the first retry.
         *
         * @param baseMillis  base delay for {@code attemptCount == 0} ({@code > 0})
         * @param capMillis   upper bound on the backoff delay ({@code >= baseMillis})
         * @param maxAttempts max retries before exhaustion ({@code >= 0};
         *                    {@code 0} disables retry — any failure goes straight to DLQ)
         * @param jitterMillis non-negative millisecond jitter source (clamped if negative)
         */
        public ExponentialBackoff {
            if (baseMillis <= 0) {
                throw new IllegalArgumentException("baseMillis must be > 0");
            }
            if (capMillis < baseMillis) {
                throw new IllegalArgumentException("capMillis must be >= baseMillis");
            }
            if (maxAttempts < 0) {
                throw new IllegalArgumentException("maxAttempts must be >= 0");
            }
            Objects.requireNonNull(jitterMillis, "jitterMillis is required");
        }

        @Override
        public long nextAttemptAt(ForwardingFailureCode code, int attemptCount, long nowMillisEpoch) {
            Objects.requireNonNull(code, "code is required");
            if (attemptCount < 0) {
                throw new IllegalArgumentException("attemptCount must be >= 0");
            }
            long delay = backoffDelay(attemptCount);
            long jitter = Math.max(0L, jitterMillis.getAsLong());
            return nowMillisEpoch + delay + jitter;
        }

        @Override
        public boolean exhausted(int attemptCount) {
            return attemptCount >= maxAttempts;
        }

        /**
         * Overflow-safe exponential backoff delay: {@code min(cap, base <<
         * attempt)}. The shift is capped at {@code 62 - (highest set bit of base)}
         * so {@code base << shift} stays positive; any value at or above the cap
         * is clamped to {@code capMillis}.
         *
         * @param attemptCount retries already recorded ({@code >= 0})
         * @return the clamped backoff delay in millis ({@code <= capMillis})
         */
        private long backoffDelay(int attemptCount) {
            int highestBit = 63 - Long.numberOfLeadingZeros(baseMillis);
            int maxSafeShift = Math.max(0, 62 - highestBit);
            int shift = Math.min(attemptCount, maxSafeShift);
            long delay = baseMillis << shift;
            return Math.min(delay, capMillis);
        }
    }
}
