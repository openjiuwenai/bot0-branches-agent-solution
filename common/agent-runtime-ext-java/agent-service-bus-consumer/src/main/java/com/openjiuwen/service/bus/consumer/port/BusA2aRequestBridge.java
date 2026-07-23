/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;

import java.util.Optional;

/**
 * Bridges a validated bus request to the standard A2A request-handler semantics.
 *
 * @since 2026-07-22
 */
public interface BusA2aRequestBridge {
    /**
     * Dispatches an event through the shared A2A request-handler semantics.
     *
     * @param envelope
     *            validated event envelope
     * @param payload
     *            decoded request payload
     *
     * @return normalized A2A dispatch result
     */
    BusDispatchResult handle(AgentBusEventEnvelope envelope, byte[] payload);

    /**
     * Returns the existing Task id carried by a continuation request.
     *
     * @param event
     *            event envelope
     * @param payload
     *            request payload
     *
     * @return existing Task id, if present
     */
    default Optional<String> requestedTaskId(AgentBusEventEnvelope event, byte[] payload) {
        return Optional.empty();
    }
}
