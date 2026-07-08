package com.openjiuwen.rdc.spi.registry;

import java.util.Objects;

/**
 * Discovery-result DTO returned by
 * {@link AgentDiscoveryService#searchByAgentId(String, String)}.
 *
 * <p>Authority: ADR-0160 decision 2 (revised by REQ-2026-004) + RB6. The
 * ICD 5 routing fields ({@code routeHandle} / {@code health} /
 * {@code contractVersion} / {@code capabilityVersion} /
 * {@code selectionHint = weight + region}) are always populated when a
 * match is found. The business definition fields ({@code agentName} /
 * {@code frameworkType}) are {@link Nullable @Nullable} —
 * {@code searchByAgentId} populates them for callers that need the full
 * card view. Per HD3-006 the DTO never carries the physical endpoint or
 * {@code routeKey} in plain form — only the opaque {@code routeHandle};
 * the forwarding layer recovers the endpoint via
 * {@link AgentDiscoveryService#resolveRouteHandle(String, String)}.
 *
 * <p>REQ-2026-004 changes (baseline-breaking):
 * <ul>
 *   <li>Renamed {@code agentType} (String) → {@code frameworkType}
 *       ({@link FrameworkType}).</li>
 *   <li>Discovery collapses to {@code searchByAgentId} single-value lookup;
 *       the dual Method A/B distinction is removed.</li>
 * </ul>
 *
 * <p>Hand-written builder (no Lombok) so the {@code spi.registry} package
 * stays pure Java (ADR-0160 decision 1).
 */
public final class AgentCardDto {

    // ---- ICD 5 routing fields (always populated on match) ----

    private final String routeHandle;
    private final String health;
    private final String contractVersion;
    private final String capabilityVersion;
    private final int weight;
    private final String region;

    // ---- Business definition fields (populated by searchByAgentId) ----

    @Nullable private final String agentName;
    @Nullable private final FrameworkType frameworkType;

    private AgentCardDto(Builder b) {
        this.routeHandle = b.routeHandle;
        this.health = b.health;
        this.contractVersion = b.contractVersion;
        this.capabilityVersion = b.capabilityVersion;
        this.weight = b.weight;
        this.region = b.region;
        this.agentName = b.agentName;
        this.frameworkType = b.frameworkType;
    }

    public String getRouteHandle() {
        return routeHandle;
    }

    public String getHealth() {
        return health;
    }

    public String getContractVersion() {
        return contractVersion;
    }

    public String getCapabilityVersion() {
        return capabilityVersion;
    }

    public int getWeight() {
        return weight;
    }

    public String getRegion() {
        return region;
    }

    @Nullable
    public String getAgentName() {
        return agentName;
    }

    @Nullable
    public FrameworkType getFrameworkType() {
        return frameworkType;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String routeHandle;
        private String health;
        private String contractVersion;
        private String capabilityVersion;
        private int weight;
        private String region;
        private String agentName;
        private FrameworkType frameworkType;

        private Builder() {
        }

        public Builder routeHandle(String routeHandle) {
            this.routeHandle = routeHandle;
            return this;
        }

        public Builder health(String health) {
            this.health = health;
            return this;
        }

        public Builder contractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
            return this;
        }

        public Builder capabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
            return this;
        }

        public Builder weight(int weight) {
            this.weight = weight;
            return this;
        }

        public Builder region(String region) {
            this.region = region;
            return this;
        }

        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        public Builder frameworkType(FrameworkType frameworkType) {
            this.frameworkType = frameworkType;
            return this;
        }

        public AgentCardDto build() {
            Objects.requireNonNull(routeHandle, "routeHandle");
            Objects.requireNonNull(health, "health");
            Objects.requireNonNull(contractVersion, "contractVersion");
            Objects.requireNonNull(capabilityVersion, "capabilityVersion");
            return new AgentCardDto(this);
        }
    }
}
