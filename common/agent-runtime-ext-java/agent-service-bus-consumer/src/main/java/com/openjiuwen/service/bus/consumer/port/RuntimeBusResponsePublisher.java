/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.port;

import com.openjiuwen.service.bus.consumer.model.BusResponseProjection;

/**
 * Publishes persisted runtime response projections through the configured bus adapter.
 *
 * @since 2026-07-22
 */
@FunctionalInterface
public interface RuntimeBusResponsePublisher {
    /**
     * Publishes a persisted projection.
     *
     * @param projection
     *            persisted projection to publish
     */
    void publish(BusResponseProjection projection);
}
