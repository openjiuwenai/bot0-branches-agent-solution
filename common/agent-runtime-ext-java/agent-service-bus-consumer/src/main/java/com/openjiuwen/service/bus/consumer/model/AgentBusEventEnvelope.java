/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.model;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import java.time.Instant;
import java.util.Map;

/**
 * Runtime-normalized view of the canonical agent-bus transport envelope. The wire contract is
 * {@code ForwardingEnvelope}; broker adapters create this view only after decoding
 * the first-class control and data fields carried by {@code BrokerInboundMessage}.
 *
 * @since 2026-07-22
 */
public record AgentBusEventEnvelope(String eventType, String messageId, String tenantId, String sourceServiceId,
        String targetServiceId, String routeHandle, String correlationId, String traceId, String idempotencyKey,
        Instant deadline, byte[] inlinePayload, String payloadRef, Map<String, String> metadata) {

    /**
     * Performs the hasPayload operation.
     *
     * @return the operation result
     */
    public boolean hasPayload() {
        return inlinePayload != null || payloadRef != null;
    }

    /**
     * Performs the parsedEventType operation.
     *
     * @return the operation result
     */
    public AgentBusEventType parsedEventType() {
        return AgentBusEventType.valueOf(eventType);
    }
}
