/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model.deployment;

/**
 * Deployment fact source port (Feat-015 0711 {@code DeploymentDiscoveryProvider}).
 * Pure Java — no Spring / JDBC imports.
 *
 * @since 0.1.0 (2026)
 */
public interface DeploymentDiscoveryProvider {
    /**
     * Stable provider identity, e.g. {@code static-dev} or {@code k8s-prod}.
     *
     * @return result
     * @since 0.1.0
     */
    String sourceId();

    /**
     * Authoritative full snapshot for reconciliation after startup, revision gap,
     * or periodic refresh. Transport / probe failures should be signaled as
     * {@link DeploymentSourceException} (mapped to {@code DEPLOYMENT_SOURCE_UNAVAILABLE}).
     *
     * @return result
     * @since 0.1.0
     */
    ListDeploymentInstancesResult listInstances();

    /**
     * Optional incremental stream. Static providers may no-op; K8s providers
     * should push {@link DeploymentInstanceEvent}s. When revision continuity
     * cannot be recovered, implementations should signal via thrown
     * {@link SourceRevisionGapException}.
     *
     * @param consumer consumer
     * @since 0.1.0
     */
    default void watchInstances(DeploymentInstanceEventConsumer consumer) {
        // optional for MVP static provider
    }
}
