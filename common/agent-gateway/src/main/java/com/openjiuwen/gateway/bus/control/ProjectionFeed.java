/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.control;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import java.util.Optional;

/**
 * Inbound projection port (simplified BrokerForwardingConsumerPort for Gateway).
 *
 * @since 2026-07-24
 */
public interface ProjectionFeed {
    /**
     * Polls the next projection matching the correlation id.
     *
     * @param correlationId gateway correlation id
     * @return the next projection event, or empty if none available
     */
    Optional<ProjectionEvent> poll(String correlationId);

    /**
     * One inbound projection event surfaced to the gateway wait loop.
     *
     * @param eventType bus event type
     * @param taskId task id when present
     * @param streamRef opaque stream ref when present
     * @param payloadRef payload ref when present
     */
    record ProjectionEvent(
            AgentBusEventType eventType,
            String taskId,
            String streamRef,
            String payloadRef) {
    }
}
