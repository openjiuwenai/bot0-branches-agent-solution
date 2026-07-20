/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model.deployment;

import java.util.List;
import java.util.Objects;

/** Full deployment instance snapshot from a provider (Feat-015 0711 scope).
 *
 * @since 0.1.0
 */
public record ListDeploymentInstancesResult(
        String sourceId,
        long sourceRevision,
        List<DeploymentInstanceObservation> observations
) {
    public ListDeploymentInstancesResult {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(observations, "observations");
        observations = List.copyOf(observations);
    }
}
