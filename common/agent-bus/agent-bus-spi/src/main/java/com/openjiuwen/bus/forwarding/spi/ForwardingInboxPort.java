/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

/**
 * Receiver-side dedup / idempotency / audit port for the C3 inbox substrate.
 *
 * <p>Dedup key is {@code (tenantId, messageId, consumerServiceId)}: distinct
 * consumers dedup independently. Implementations MUST scope every operation by
 * {@code tenantId} (Rule R-C.c; cross-tenant reads fail explicitly, never fall
 * back) and MUST call {@code ForwardingStateMachine} to validate transitions.
 *
 * <p>Authority: {@code ICD-Agent-Bus-Forwarding-Runtime};
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §4.2/§8}.
 *
 * @since 0.1.0
 */
public interface ForwardingInboxPort {
    /**
     * Receive an envelope on the consumer side, dedup, and return the resulting
     * inbox status. First arrival → {@link ForwardingStatus.Inbox#RECEIVED};
     * duplicate {@code (tenantId, messageId, consumerServiceId)} →
     * {@link ForwardingStatus.Inbox#DUPLICATE_SUPPRESSED}.
     *
     * @param envelope the forwarding envelope to receive
     * @param consumerServiceId the consumer service identity scoping the dedup key
     * @param nowMillisEpoch the receive instant, in milliseconds since the epoch
     * @return the resulting inbox status (RECEIVED or DUPLICATE_SUPPRESSED)
     */
    ForwardingStatus.Inbox receive(ForwardingEnvelope envelope,
                                   String consumerServiceId, long nowMillisEpoch);

    /**
     * Mark a RECEIVED entry CONSUMED (terminal).
     *
     * @param id the message id of the inbox entry
     * @param tenantId the tenant identity scoping the dedup key
     * @param consumerServiceId the consumer service identity scoping the dedup key
     * @return the resulting inbox status (CONSUMED)
     */
    ForwardingStatus.Inbox markConsumed(ForwardingMessageId id, String tenantId,
                                        String consumerServiceId);

    /**
     * Mark a RECEIVED entry REJECTED (terminal) with a failure code.
     *
     * @param id the message id of the inbox entry
     * @param tenantId the tenant identity scoping the dedup key
     * @param consumerServiceId the consumer service identity scoping the dedup key
     * @param code the failure code explaining the rejection
     * @return the resulting inbox status (REJECTED)
     */
    ForwardingStatus.Inbox markRejected(ForwardingMessageId id, String tenantId,
                                        String consumerServiceId, ForwardingFailureCode code);

    /**
     * Current status of an inbox entry (tenant-scoped).
     *
     * @param id the message id of the inbox entry
     * @param tenantId the tenant identity scoping the dedup key
     * @param consumerServiceId the consumer service identity scoping the dedup key
     * @return the current inbox status
     */
    ForwardingStatus.Inbox statusOf(ForwardingMessageId id, String tenantId,
                                    String consumerServiceId);
}
