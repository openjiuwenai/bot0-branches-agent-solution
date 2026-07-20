/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Optional load-balancing hint for callers (Feat-015 0711 {@code selectionHint}).
 *
 * @since 0.1.0 (2026)
 */
public record SelectionHint(
        int weight,
        @Nullable String region
) {
    public SelectionHint {
        if (weight < 0) {
    throw new IllegalArgumentException("weight must be >= 0");
}
    }
}
