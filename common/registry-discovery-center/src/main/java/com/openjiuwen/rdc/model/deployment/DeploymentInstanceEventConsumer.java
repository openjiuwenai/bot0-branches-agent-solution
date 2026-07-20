package com.openjiuwen.rdc.model.deployment;

/**
 * Consumes incremental deployment instance events from a
 * {@link DeploymentDiscoveryProvider}.
 */
@FunctionalInterface
public interface DeploymentInstanceEventConsumer {

    void onEvent(DeploymentInstanceEvent event);
}
