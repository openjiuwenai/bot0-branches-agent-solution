/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Caller health filter for {@link DiscoveryQuery}. When unset, registry returns
 * {@link EffectiveHealth#HEALTHY} and {@link EffectiveHealth#DEGRADED} candidates.
 *
 * @since 0.1.0 (2026)
  */
public enum HealthRequirement {
    HEALTHY,
    HEALTHY_OR_DEGRADED,
    ANY
}
