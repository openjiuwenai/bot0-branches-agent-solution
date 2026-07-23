package com.openjiuwen.gateway.bus.control;

import java.util.ArrayList;
import java.util.List;

import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;

/** Test double for ForwardingOutboxPort: records enqueues; configurable to fail. */
public class FakeForwardingOutboxPort implements ForwardingOutboxPort {
    public final List<ForwardingEnvelope> enqueued = new ArrayList<>();
    public boolean failNext = false;
    public ForwardingFailureCode failCode = null;

    @Override
    public ForwardingReceipt enqueue(ForwardingEnvelope envelope, String sourceServiceId,
                                     String targetServiceId, long nowMillisEpoch) {
        if (failNext) {
            throw new RuntimeException("fake enqueue failure");
        }
        enqueued.add(envelope);
        return new ForwardingReceipt(envelope.messageId(), envelope.tenantId(), true, null, nowMillisEpoch);
    }

    @Override
    public com.openjiuwen.bus.forwarding.spi.ForwardingStatus.Outbox markAcked(
            ForwardingMessageId id, String tenantId, String leaseOwner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjiuwen.bus.forwarding.spi.ForwardingStatus.Outbox scheduleRetry(
            ForwardingMessageId id, String tenantId, String leaseOwner,
            ForwardingFailureCode code, long nextAttemptAtMillisEpoch) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjiuwen.bus.forwarding.spi.ForwardingStatus.Outbox moveToDlq(
            ForwardingMessageId id, String tenantId, String leaseOwner, ForwardingFailureCode code) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjiuwen.bus.forwarding.spi.ForwardingStatus.Outbox markExpired(
            ForwardingMessageId id, String tenantId, String leaseOwner) {
        throw new UnsupportedOperationException();
    }

    @Override
    public com.openjiuwen.bus.forwarding.spi.ForwardingStatus.Outbox statusOf(
            ForwardingMessageId id, String tenantId) {
        throw new UnsupportedOperationException();
    }
}
