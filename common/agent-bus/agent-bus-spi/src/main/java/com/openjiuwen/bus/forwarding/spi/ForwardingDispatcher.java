/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.bus.forwarding.spi;

/**
 * Accept / enqueue entry for the C3 forwarding substrate — the <em>gateway
 * role</em> (MI8-003).
 *
 * <p>This interface accepts a forwarding envelope, validates it, and writes it
 * into the outbox, returning the synchronous ack receipt. It is deliberately a
 * thin accept / enqueue entry: it does <strong>not</strong> drive the outbox
 * through DISPATCHING → ACK / RETRY / DLQ / EXPIRED. That claim / deliver /
 * ack / retry half of the lifecycle is the separate {@code ForwardingDispatcherWorker}
 * (runtime package), which consumes a {@code ForwardingOutboxClaimPort} and an
 * abstract {@code ForwardingDeliveryPort}. Keeping the two roles in separate
 * types resolves the Stage 7 javadoc / method mismatch: {@code dispatch} here
 * means "accept into the outbox", not "deliver to the receiver".
 *
 * <p>The gateway never bypasses {@link ForwardingRouteHandle} to a physical
 * endpoint and never writes Task execution state.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §3/§8}.
 *
 * @since 0.1.0
 */
public interface ForwardingDispatcher {
    /**
     * Accept a forwarding envelope into the outbox and return the synchronous
     * ack receipt. {@code sourceServiceId} and {@code targetServiceId} are
     * written onto the resulting {@link ForwardingOutboxRecord} (MI8-002).
     * Delivery orchestration is the {@code ForwardingDispatcherWorker} role.
     *
     * @param envelope the forwarding envelope to accept into the outbox
     * @param sourceServiceId the source service identity stamped onto the outbox record
     * @param targetServiceId the target service identity stamped onto the outbox record
     * @param nowMillisEpoch the accept instant, in milliseconds since the epoch
     * @return the synchronous ack receipt for the enqueued outbox record
     */
    ForwardingReceipt dispatch(ForwardingEnvelope envelope, String sourceServiceId,
                               String targetServiceId, long nowMillisEpoch);
}
