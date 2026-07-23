/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

/**
 * Explicit target identity used by bus envelope validation and routing.
 *
 * @since 2026-07-22
 */
public interface RuntimeBusTargetIdentity {
    /**
     * Returns the service identity targeted by inbound events.
     *
     * @return target service identity
     */
    String serviceId();
}
