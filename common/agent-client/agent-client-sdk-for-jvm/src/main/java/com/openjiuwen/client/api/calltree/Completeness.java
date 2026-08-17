/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api.calltree;

/**
 * 调用树相对于服务端实际调用过程的完整度。
 *
 * @since 2026-07-27
 */
public enum Completeness {
    LIVE,
    RECOVERED_REPLAYED,
    RECOVERED_CURRENT_STATE,
    PARTIAL,
    UNAVAILABLE_FOR_MODE,
    DEGRADED
}
