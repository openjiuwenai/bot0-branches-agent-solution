package com.openjiuwen.bus.forwarding.spi;

import java.util.Objects;

/**
 * Receiver-side dedup / idempotency / audit record for the C3 inbox substrate
 * (Stage 8 / 9).
 *
 * <p>Mirrors the inbox record schema of {@code ICD-Agent-Bus-Forwarding-Runtime}
 * field-for-field: the receiver writes a record on first arrival
 * ({@link ForwardingInboxPort#receive}) keyed by the dedup key
 * {@code (tenantId, messageId, consumerServiceId)}, and mutates it through the
 * inbox state machine. {@code idempotencyKey} is an audit-only envelope field
 * (MI8-004); the dedup key is the triple below, not {@code idempotencyKey}.
 *
 * <p>Stage 9 (MI9-003)固化 the runtime ICD condition-field rules in the compact
 * constructor: {@code CONSUMED} requires a positive {@code consumedAtMillisEpoch}
 * and no failure code; {@code DUPLICATE_SUPPRESSED} requires the
 * {@code DUPLICATE_SUPPRESSED} failure code; {@code REJECTED} requires a
 * non-null failure code; {@code RECEIVED} carries no failure code and
 * {@code consumedAtMillisEpoch == 0} (unset).
 *
 * <p>Forbidden-payload invariant (HD4): same as the outbox record — no payload
 * body, no token stream, no Task execution state, no physical endpoint.
 *
 * <p>Authority: {@code ICD-Agent-Bus-Forwarding-Runtime} (inbox record fields);
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.2};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §3}.
 */
// scope: forwarding substrate — dedup / audit inbox record; never a payload body
public record ForwardingInboxRecord(
        String tenantId,
        ForwardingMessageId messageId,
        String consumerServiceId,
        ForwardingStatus.Inbox status,
        long receivedAtMillisEpoch,
        long consumedAtMillisEpoch,
        ForwardingFailureCode failureCode
) {
    public ForwardingInboxRecord {
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        Objects.requireNonNull(messageId, "messageId is required");
        Objects.requireNonNull(consumerServiceId, "consumerServiceId is required");
        if (consumerServiceId.isBlank()) {
            throw new IllegalArgumentException("consumerServiceId must not be blank");
        }
        Objects.requireNonNull(status, "status is required");
        validateStatusInvariants(status, consumedAtMillisEpoch, failureCode);
    }

    /**
     * Per-status condition-field invariants (MI9-003). {@code consumedAtMillisEpoch == 0}
     * denotes "unset" (long primitive); only {@code CONSUMED} requires a positive value.
     */
    static void validateStatusInvariants(ForwardingStatus.Inbox status,
                                         long consumedAtMillisEpoch,
                                         ForwardingFailureCode failureCode) {
        switch (status) {
            case RECEIVED -> requireCondition(failureCode == null,
                    "RECEIVED inbox record must not carry a failureCode");
            case CONSUMED -> {
                requireCondition(failureCode == null,
                        "CONSUMED inbox record must not carry a failureCode");
                requireCondition(consumedAtMillisEpoch > 0,
                        "CONSUMED inbox record requires consumedAtMillisEpoch > 0");
            }
            case DUPLICATE_SUPPRESSED -> requireCondition(
                    failureCode == ForwardingFailureCode.DUPLICATE_SUPPRESSED,
                    "DUPLICATE_SUPPRESSED inbox record requires failureCode DUPLICATE_SUPPRESSED");
            case REJECTED -> requireCondition(failureCode != null,
                    "REJECTED inbox record requires a non-null failureCode");
        }
    }

    private static void requireCondition(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Whether this inbox record is in a terminal state. */
    public boolean isTerminal() {
        return status == ForwardingStatus.Inbox.DUPLICATE_SUPPRESSED
                || status == ForwardingStatus.Inbox.CONSUMED
                || status == ForwardingStatus.Inbox.REJECTED;
    }
}
