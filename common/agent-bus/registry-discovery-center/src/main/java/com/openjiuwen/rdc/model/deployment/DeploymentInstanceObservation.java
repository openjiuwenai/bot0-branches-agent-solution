/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model.deployment;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider observation of a single physical runtime instance (Feat-015 0711
 * {@code DeploymentInstanceObservation}).
 *
 * @since 0.1.0 (2026)
 * @param tenantId tenantId
 * @param serviceId serviceId
 * @param instanceId instanceId
 * @param internalBaseUrl internalBaseUrl
 * @param deploymentVersion deploymentVersion
 * @param readiness readiness
 * @param sourceId sourceId
 * @param sourceRevision sourceRevision
 * @param observedAt observedAt
 * @return result
 */
public record DeploymentInstanceObservation(
        String tenantId,
        String serviceId,
        String instanceId,
        String internalBaseUrl,
        String deploymentVersion,
        Readiness readiness,
        String sourceId,
        long sourceRevision,
        Instant observedAt
) {
    public DeploymentInstanceObservation {
        Objects.requireNonNull(readiness, "readiness");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    /**
     * reconcileKey.
     *
     * @return result
     * @since 0.1.0
     */
    public String reconcileKey() {
        return tenantId + ":" + serviceId + ":" + instanceId;
    }
}
