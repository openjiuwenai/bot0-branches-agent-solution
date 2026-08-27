/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.caller;

import com.openjiuwen.service.bus.consumer.projection.AgentBusProjectionJsonCodec;

/**
 * Decodes the FEAT-017 JSON response-projection envelope carried by Agent Bus.
 *
 * @since 2026-08-04
 */
final class AgentBusProjectionDecoder {
    private final AgentBusProjectionJsonCodec codec = new AgentBusProjectionJsonCodec();

    AgentBusProjectionJsonCodec.DecodedProjection decode(String inlinePayload, String eventType) {
        return codec.decode(inlinePayload, eventType);
    }
}
