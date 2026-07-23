/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;

import java.util.Optional;

/**
 * Minimal broker-neutral receiver contract for the agent-bus migration target.
 *
 * @since 2026-07-22
 */
public interface BrokerDeliveryPort {
    /**
     * Polls the next delivery for a consumer scope.
     *
     * @param consumerServiceId
     *            consumer service identity
     * @param tenantId
     *            tenant identity
     * @param nowEpochMillis
     *            current epoch time in milliseconds
     *
     * @return the next delivery, if available
     */
    Optional<Delivery> poll(String consumerServiceId, String tenantId, long nowEpochMillis);

    /**
     * Acknowledges a successfully handled delivery.
     *
     * @param delivery
     *            delivery to acknowledge
     */
    void commit(Delivery delivery);

    /**
     * Rejects a delivery with a stable reason.
     *
     * @param delivery
     *            delivery to reject
     * @param reason
     *            rejection reason
     */
    void reject(Delivery delivery, RejectReason reason);

    /** Carries an envelope and its resolved broker payload. */
    record Delivery(AgentBusEventEnvelope envelope, byte[] payload) {
    }

    /** Lists broker-neutral rejection reasons. */
    enum RejectReason {
        INVALID_ENVELOPE, INVALID_PAYLOAD, PROCESSING_FAILED
    }
}
