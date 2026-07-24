/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double for ForwardingOutboxPort: records enqueues; configurable to fail.
 *
 * @since 2026-07-24
 */
public class FakeForwardingOutboxPort implements ForwardingOutboxPort {
    private final List<ForwardingEnvelope> enqueued = new ArrayList<>();
    private boolean failNext = false;

    /** @return enqueued envelopes in order */
    public List<ForwardingEnvelope> enqueued() {
        return enqueued;
    }

    /** @param failNext when {@code true}, the next enqueue throws */
    public void setFailNext(boolean failNext) {
        this.failNext = failNext;
    }

    @Override
    public ForwardingReceipt enqueue(ForwardingEnvelope envelope, String sourceServiceId,
                                     String targetServiceId, long nowMillisEpoch) {
        if (failNext) {
            throw new IllegalStateException("fake enqueue failure");
        }
        enqueued.add(envelope);
        return new ForwardingReceipt(envelope.messageId(), envelope.tenantId(), true, null, nowMillisEpoch);
    }

    @Override
    public ForwardingStatus.Outbox markAcked(ForwardingMessageId id, String tenantId, String leaseOwner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ForwardingStatus.Outbox scheduleRetry(
            ForwardingMessageId id, String tenantId, String leaseOwner,
            ForwardingFailureCode code, long nextAttemptAtMillisEpoch) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ForwardingStatus.Outbox moveToDlq(
            ForwardingMessageId id, String tenantId, String leaseOwner, ForwardingFailureCode code) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ForwardingStatus.Outbox markExpired(ForwardingMessageId id, String tenantId, String leaseOwner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ForwardingStatus.Outbox statusOf(ForwardingMessageId id, String tenantId) {
        throw new UnsupportedOperationException();
    }
}
