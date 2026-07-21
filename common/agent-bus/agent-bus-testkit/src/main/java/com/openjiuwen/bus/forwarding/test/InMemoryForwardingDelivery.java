/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.test;

import com.openjiuwen.bus.forwarding.spi.ForwardingDeliveryPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingDeliveryResult;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * In-memory fake for {@link ForwardingDeliveryPort} — NON-PRODUCTION.
 *
 * <p>Let the dispatcher worker be driven through the ACK / RETRY / DLQ / EXPIRED
 * outcomes without any real transport. A result is selected per
 * {@code messageId} if configured, else the {@linkplain #setDefault default}. The
 * fake consumes only the {@link ForwardingOutboxRecord} — it never touches a
 * physical endpoint and never writes Task execution state.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §5}.
 *
 * @since 0.1.0
 */
// non-production — test fixture only; real transport binding is a later stage
public final class InMemoryForwardingDelivery implements ForwardingDeliveryPort {
    private ForwardingDeliveryResult defaultResult = ForwardingDeliveryResult.acked();
    private final Map<String, ForwardingDeliveryResult> perMessage = new HashMap<>();

    /**
     * Set the outcome returned for any message without an explicit override.
     *
     * @param result the default delivery result (required)
     */
    public void setDefault(ForwardingDeliveryResult result) {
        this.defaultResult = Objects.requireNonNull(result, "result is required");
    }

    /**
     * Pin the outcome for a specific {@code messageId}.
     *
     * @param messageId the message id to pin the result for (required)
     * @param result    the delivery result to return for this message (required)
     */
    public void put(String messageId, ForwardingDeliveryResult result) {
        Objects.requireNonNull(messageId, "messageId is required");
        Objects.requireNonNull(result, "result is required");
        perMessage.put(messageId, result);
    }

    @Override
    public ForwardingDeliveryResult deliver(ForwardingOutboxRecord record, long nowMillisEpoch) {
        Objects.requireNonNull(record, "record is required");
        return perMessage.getOrDefault(record.messageId().value(), defaultResult);
    }
}
