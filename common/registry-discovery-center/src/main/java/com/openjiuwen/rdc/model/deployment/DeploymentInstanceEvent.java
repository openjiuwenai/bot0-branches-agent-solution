package com.openjiuwen.rdc.model.deployment;

import java.util.Objects;

/** Incremental deployment instance event (Feat-015 0711 scope). */
public record DeploymentInstanceEvent(
        DeploymentInstanceEventType type,
        DeploymentInstanceObservation observation
) {
    public DeploymentInstanceEvent {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(observation, "observation");
    }
}
