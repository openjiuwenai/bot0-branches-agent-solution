/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Card snapshot freshness (Feat-015 0713 scope §5.1.4).
 *
 * @since 0.1.0 (2026)
 */
public enum Freshness {
    FRESH,
    STALE_SOURCE,
    STALE_CARD
}
