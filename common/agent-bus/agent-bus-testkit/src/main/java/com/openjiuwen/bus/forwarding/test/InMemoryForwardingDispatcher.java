/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.test;

import com.openjiuwen.bus.forwarding.spi.ForwardingDispatcher;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;

import java.util.Objects;

/**
 * In-memory test double for {@link ForwardingDispatcher} — NON-PRODUCTION.
 *
 * <p>The dispatcher is the accept / enqueue gateway role (MI8-003): this double
 * delegates straight to {@link ForwardingOutboxPort#enqueue}, projecting the
 * caller's source / target service ids onto the outbox record. Real delivery
 * orchestration (claim / deliver / ack / retry) is the separate
 * {@code ForwardingDispatcherWorker} role, exercised with an
 * {@link InMemoryForwardingDelivery} fake.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §3}.
 *
 * @since 0.1.0
 */
// non-production — test fixture only; real delivery binding is a later stage
public final class InMemoryForwardingDispatcher implements ForwardingDispatcher {
    private final ForwardingOutboxPort outbox;

    public InMemoryForwardingDispatcher(ForwardingOutboxPort outbox) {
        this.outbox = Objects.requireNonNull(outbox, "outbox is required");
    }

    @Override
    public ForwardingReceipt dispatch(ForwardingEnvelope envelope, String sourceServiceId,
                                      String targetServiceId, long nowMillisEpoch) {
        return outbox.enqueue(envelope, sourceServiceId, targetServiceId, nowMillisEpoch);
    }
}
