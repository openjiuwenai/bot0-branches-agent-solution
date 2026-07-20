package com.openjiuwen.rdc.model.deployment;

import java.time.Instant;
import java.util.Objects;

/**
 * Provider observation of a single physical runtime instance (Feat-015 0711
 * {@code DeploymentInstanceObservation}).
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

    public String reconcileKey() {
        return tenantId + ":" + serviceId + ":" + instanceId;
    }
}
