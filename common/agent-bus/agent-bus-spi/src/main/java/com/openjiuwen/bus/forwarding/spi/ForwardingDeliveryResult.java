/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

import java.util.Objects;

/**
 * Outcome of a single delivery attempt, returned by {@link ForwardingDeliveryPort}
 * and consumed by {@code ForwardingDispatcherWorker} to drive the outbox state
 * machine (Stage 8 plan §3 slice 5).
 *
 * <p>One of four outcomes, each mapped to a state-machine event:
 * <ul>
 *   <li>{@link Outcome#ACKED} → mark ACKED (terminal-success).</li>
 *   <li>{@link Outcome#RETRY_SCHEDULED} → schedule a retry (retryable failure;
 *       carries the failure code; <em>when</em> to retry is decided by
 *       {@code ForwardingRetryPolicy}, Stage 14 — the result no longer carries a
 *       next-attempt instant).</li>
 *   <li>{@link Outcome#DLQ} → move to DLQ (non-retryable failure or retries
 *       exhausted; carries the failure code).</li>
 *   <li>{@link Outcome#EXPIRED} → mark EXPIRED (envelope deadline exceeded).</li>
 * </ul>
 * The compact constructor pins the failure-code invariants per outcome so the
 * worker can switch on {@link #outcome()} without re-validating.
 *
 * <p><b>Stage 14 (separation of concerns).</b> Before Stage 14 this record also
 * carried {@code nextAttemptAtMillisEpoch} on a {@code RETRY_SCHEDULED} result,
 * so the delivery binding chose when to retry. Retry timing is governance owned
 * by {@code ForwardingRetryPolicy}; the result reports <em>what</em> happened (a
 * retryable failure), the policy decides <em>when</em> to retry. The
 * {@code nextAttemptAt} field has been removed accordingly. (The outbox record
 * still carries {@code nextAttemptAtMillisEpoch} as a persisted DB field — that
 * is the storage of the policy's decision, a separate concern from the delivery
 * result.)
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.1/§7};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §5}.
 */
public record ForwardingDeliveryResult(Outcome outcome, ForwardingFailureCode failureCode) {

    public ForwardingDeliveryResult {
        Objects.requireNonNull(outcome, "outcome is required");
        switch (outcome) {
            case ACKED -> {
                if (failureCode != null) {
                    throw new IllegalArgumentException("ACKED result must not carry a failureCode");
                }
            }
            case RETRY_SCHEDULED -> {
                if (failureCode == null) {
                    throw new IllegalArgumentException(
                            "RETRY_SCHEDULED result requires a retryable failureCode");
                }
                if (!failureCode.retryable()) {
                    throw new IllegalArgumentException(
                            "RETRY_SCHEDULED result requires a retryable failureCode; "
                            + failureCode + " is not retryable (MI9-004)");
                }
            }
            case DLQ -> {
                if (failureCode == null) {
                    throw new IllegalArgumentException("DLQ result requires a failureCode");
                }
                if (failureCode.dedup()) {
                    throw new IllegalArgumentException(
                            "DLQ result must not carry a dedup failureCode (DUPLICATE_SUPPRESSED "
                            + "is a dedup outcome, not a delivery failure; MI9-004)");
                }
            }
            case EXPIRED -> {
                if (failureCode != null) {
                    throw new IllegalArgumentException("EXPIRED result must not carry a failureCode");
                }
            }
        }
    }

    /** Delivery outcome — maps 1:1 to an outbox state-machine terminal / retry event. */
    public enum Outcome {
        ACKED, RETRY_SCHEDULED, DLQ, EXPIRED
    }

    /**
     * Successful synchronous ack (ICD Delivery Model).
     *
     * @return a delivery result with outcome {@link Outcome#ACKED} and no failure code
     */
    public static ForwardingDeliveryResult acked() {
        return new ForwardingDeliveryResult(Outcome.ACKED, null);
    }

    /**
     * Retryable failure. The code MUST be {@link ForwardingFailureCode#retryable()
     * retryable} (MI9-004). <em>When</em> the retry fires is decided by
     * {@code ForwardingRetryPolicy} (Stage 14); the result no longer carries a
     * next-attempt instant.
     *
     * @param failureCode the retryable failure code; must not be {@code null} and must be retryable
     * @return a delivery result with outcome {@link Outcome#RETRY_SCHEDULED} carrying the failure code
     */
    public static ForwardingDeliveryResult retry(ForwardingFailureCode failureCode) {
        return new ForwardingDeliveryResult(Outcome.RETRY_SCHEDULED, failureCode);
    }

    /**
     * Non-retryable failure / retries exhausted → DLQ. Accepts a
     * {@link ForwardingFailureCode#nonRetryable() non-retryable} code or a
     * retryable code whose retries have been exhausted; rejects the dedup
     * outcome (MI9-004).
     *
     * @param failureCode the failure code; must not be {@code null} and must not be a dedup outcome
     * @return a delivery result with outcome {@link Outcome#DLQ} carrying the failure code
     */
    public static ForwardingDeliveryResult dlq(ForwardingFailureCode failureCode) {
        return new ForwardingDeliveryResult(Outcome.DLQ, failureCode);
    }

    /**
     * Envelope deadline exceeded → EXPIRED.
     *
     * @return a delivery result with outcome {@link Outcome#EXPIRED} and no failure code
     */
    public static ForwardingDeliveryResult expired() {
        return new ForwardingDeliveryResult(Outcome.EXPIRED, null);
    }
}
