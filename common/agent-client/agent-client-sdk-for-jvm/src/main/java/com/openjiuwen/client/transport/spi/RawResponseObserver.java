/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.transport.spi;

/** Receives best-effort wire response observations asynchronously and in queue order. */
@FunctionalInterface
public interface RawResponseObserver {
    void onResponse(RawResponseEvent event);
}
