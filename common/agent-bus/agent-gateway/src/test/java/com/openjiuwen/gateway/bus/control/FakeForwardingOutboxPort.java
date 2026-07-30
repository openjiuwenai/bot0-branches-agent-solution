/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxClaimPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Test double for the durable outbox: implements BOTH {@link ForwardingOutboxPort} (enqueue /
 * ack / retry) AND {@link ForwardingOutboxClaimPort} (claim / release) — mirrors the real
 * {@code JdbcForwardingOutbox} which is a single bean for both ports. Records enqueue / ack /
 * retry / release / claim so the {@code GatewayOutboxDispatcher} test can assert side-effects.
 *
 * @since 2026-07-28
 */
public class FakeForwardingOutboxPort implements ForwardingOutboxPort, ForwardingOutboxClaimPort {
    private final List<ForwardingEnvelope> enqueued = new ArrayList<>();
    private final Queue<ForwardingOutboxRecord> claimable = new LinkedList<>();
    private final List<ForwardingMessageId> acked = new ArrayList<>();
    private final List<ForwardingMessageId> retryScheduled = new ArrayList<>();
    private final List<ForwardingMessageId> released = new ArrayList<>();
    private boolean failNext = false;

    /**
     * Returns envelopes recorded by enqueue, in order.
     *
     * @return enqueued envelopes in order
     */
    public List<ForwardingEnvelope> enqueued() {
        return enqueued;
    }

    /**
     * Returns message ids mark-acked by the dispatcher, in order.
     *
     * @return acked message ids
     */
    public List<ForwardingMessageId> acked() {
        return acked;
    }

    /**
     * Returns message ids retry-scheduled by the dispatcher, in order.
     *
     * @return retry-scheduled message ids
     */
    public List<ForwardingMessageId> retryScheduled() {
        return retryScheduled;
    }

    /**
     * Returns message ids whose lease was released (skipped) by the dispatcher.
     *
     * @return released message ids
     */
    public List<ForwardingMessageId> released() {
        return released;
    }

    /**
     * Configures the next enqueue call to fail.
     *
     * @param failNext when {@code true}, the next enqueue throws
     */
    public void setFailNext(boolean failNext) {
        this.failNext = failNext;
    }

    /**
     * Sets records the next {@link #claimDue} returns (drained up to limit per call).
     *
     * @param records records to claim
     */
    public void setClaimable(List<ForwardingOutboxRecord> records) {
        claimable.addAll(records);
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
        acked.add(id);
        return ForwardingStatus.Outbox.ACKED;
    }

    @Override
    public ForwardingStatus.Outbox scheduleRetry(
            ForwardingMessageId id, String tenantId, String leaseOwner,
            ForwardingFailureCode code, long nextAttemptAtMillisEpoch) {
        retryScheduled.add(id);
        return ForwardingStatus.Outbox.RETRY_SCHEDULED;
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

    @Override
    public List<ForwardingOutboxRecord> claimDue(String tenantId, long nowMillisEpoch, int limit,
                                                 String leaseOwner, long leaseUntilMillisEpoch) {
        List<ForwardingOutboxRecord> out = new ArrayList<>();
        for (int i = 0; i < limit && !claimable.isEmpty(); i++) {
            out.add(claimable.poll());
        }
        return out;
    }

    @Override
    public boolean renewLease(ForwardingMessageId id, String tenantId, String leaseOwner,
                              long leaseUntilMillisEpoch) {
        return true;
    }

    @Override
    public boolean releaseLease(ForwardingMessageId id, String tenantId, String leaseOwner) {
        released.add(id);
        return true;
    }
}
