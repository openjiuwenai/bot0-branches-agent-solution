/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;

/**
 * Extends the A2A bridge when the upstream handler supports caller-supplied Task IDs.
 *
 * @since 2026-07-22
 */
public interface TaskIdAwareBusA2aRequestBridge extends BusA2aRequestBridge {
    /**
     * Dispatches with a caller-reserved Task identity.
     *
     * @param envelope
     *            event envelope
     * @param payload
     *            request payload
     * @param reservedTaskId
     *            reserved Task identity
     *
     * @return normalized dispatch result
     */
    BusDispatchResult handle(AgentBusEventEnvelope envelope, byte[] payload, String reservedTaskId);

    /** {@inheritDoc} */
    @Override
    default BusDispatchResult handle(AgentBusEventEnvelope envelope, byte[] payload) {
        return handle(envelope, payload, null);
    }
}
