package com.openjiuwen.rdc.model;

/**
 * Structured discovery result outcome (Feat-015 0713 scope §5.1.6).
 * Zero candidates with one of the {@code NO_*} values is a normal business
 * result — not a {@link RegistryFailure}.
 */
public enum DiscoveryOutcome {
    SUCCESS,
    NO_MATCH,
    VERSION_UNAVAILABLE,
    CONSTRAINT_UNAVAILABLE
}
