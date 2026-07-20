package com.openjiuwen.rdc.model;

/**
 * Caller health filter for {@link DiscoveryQuery}. When unset, registry returns
 * {@link EffectiveHealth#HEALTHY} and {@link EffectiveHealth#DEGRADED} candidates.
 */
public enum HealthRequirement {
    HEALTHY,
    HEALTHY_OR_DEGRADED,
    ANY
}
