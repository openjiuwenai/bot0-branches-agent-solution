/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

import com.openjiuwen.service.bus.consumer.model.AgentBusEventEnvelope;

/**
 * Resolves an inline or externally referenced event payload.
 *
 * @since 2026-07-22
 */
@FunctionalInterface
public interface BusPayloadResolver {
    /**
     * Resolves the request payload.
     *
     * @param envelope
     *            event envelope
     *
     * @return resolved payload bytes
     */
    byte[] resolve(AgentBusEventEnvelope envelope);
}
