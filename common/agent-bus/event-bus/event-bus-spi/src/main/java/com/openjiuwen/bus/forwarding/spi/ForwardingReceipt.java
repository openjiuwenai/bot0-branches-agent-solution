/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.spi;

import java.util.Objects;

/**
 * Synchronous acknowledgement receipt for a forwarding enqueue (ICD Delivery
 * Model: synchronous ack — the substrate confirms receipt and durable enqueue,
 * not processing completion).
 *
 * <p>An accepted receipt carries no failure code; a rejected receipt must carry
 * one. The compact constructor enforces this invariant.
 *
 * <p>Authority: {@code ICD-Agent-Bus-Forwarding} (Delivery Model);
 * {@code architecture/L2-Low-Level-Design/agent-bus/forwarding-outbox-inbox.md §8}.
 */
// scope: forwarding substrate — synchronous ack receipt
public record ForwardingReceipt(
        ForwardingMessageId messageId,
        String tenantId,
        boolean accepted,
        ForwardingFailureCode failureCode,
        long acceptedAtMillisEpoch
) {
    public ForwardingReceipt {
        Objects.requireNonNull(messageId, "messageId is required");
        Objects.requireNonNull(tenantId, "tenantId is required");
        if (tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (accepted && failureCode != null) {
            throw new IllegalArgumentException(
                    "accepted receipt must not carry a failureCode");
        }
        if (!accepted && failureCode == null) {
            throw new IllegalArgumentException(
                    "rejected receipt must carry a failureCode");
        }
    }

    /**
     * Accepted receipt factory (no failure code).
     *
     * @param messageId the id of the message acknowledged by the substrate
     * @param tenantId the tenant scope of the message
     * @param acceptedAtMillisEpoch the epoch millis at which the substrate accepted the message
     * @return an accepted receipt carrying no failure code
     */
    public static ForwardingReceipt accepted(ForwardingMessageId messageId,
                                             String tenantId, long acceptedAtMillisEpoch) {
        return new ForwardingReceipt(messageId, tenantId, true, null, acceptedAtMillisEpoch);
    }

    /**
     * Rejected receipt factory (failure code required).
     *
     * @param messageId the id of the message rejected by the substrate
     * @param tenantId the tenant scope of the message
     * @param failureCode the reason the message was rejected (required)
     * @param acceptedAtMillisEpoch the epoch millis at which the substrate rejected the message
     * @return a rejected receipt carrying the failure code
     */
    public static ForwardingReceipt rejected(ForwardingMessageId messageId,
                                             String tenantId, ForwardingFailureCode failureCode,
                                             long acceptedAtMillisEpoch) {
        return new ForwardingReceipt(messageId, tenantId, false, failureCode, acceptedAtMillisEpoch);
    }
}
