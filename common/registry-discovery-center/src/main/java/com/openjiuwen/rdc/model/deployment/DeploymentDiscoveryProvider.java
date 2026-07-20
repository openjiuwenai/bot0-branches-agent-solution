package com.openjiuwen.rdc.model.deployment;

/**
 * Deployment fact source port (Feat-015 0711 {@code DeploymentDiscoveryProvider}).
 * Pure Java — no Spring / JDBC imports.
 */
public interface DeploymentDiscoveryProvider {

    /** Stable provider identity, e.g. {@code static-dev} or {@code k8s-prod}. */
    String sourceId();

    /**
     * Authoritative full snapshot for reconciliation after startup, revision gap,
     * or periodic refresh.
     */
    ListDeploymentInstancesResult listInstances();

    /**
     * Optional incremental stream. Static providers may no-op; K8s providers
     * should push {@link DeploymentInstanceEvent}s. When revision continuity
     * cannot be recovered, implementations should signal via thrown
     * {@link SourceRevisionGapException}.
     */
    default void watchInstances(DeploymentInstanceEventConsumer consumer) {
        // optional for MVP static provider
    }
}
