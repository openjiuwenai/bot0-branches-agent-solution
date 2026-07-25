/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model.deployment;

import java.util.Objects;

/**
 * Incremental deployment instance event (Feat-015 0711 scope).
 *
 * @since 0.1.0 (2026)
 * @param type type
 * @param observation observation
 * @return result
 */
public record DeploymentInstanceEvent(
        DeploymentInstanceEventType type,
        DeploymentInstanceObservation observation
) {
    public DeploymentInstanceEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(observation, "observation");
    }
}
