/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.spi;

/**
 * Receives best-effort wire response observations asynchronously and in queue order.
 *
 * @since 2026-07-27
 */
@FunctionalInterface
public interface RawResponseObserver {
    /**
     * Handles one raw response observation.
     *
     * @param event observed response
     */
    void onResponse(RawResponseEvent event);
}
