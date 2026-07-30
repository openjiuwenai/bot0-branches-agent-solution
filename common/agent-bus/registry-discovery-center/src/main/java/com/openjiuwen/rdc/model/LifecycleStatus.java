/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Registry entry lifecycle (Feat-015 0711 scope §5.1.5).
 *
 * @since 0.1.0 (2026)
 */
public enum LifecycleStatus {
    PENDING,
    ACTIVE,
    DRAINING,
    REMOVED
}
