package com.openjiuwen.rdc.model;

import java.util.Objects;

/**
 * Discovery-result DTO returned by
 * {@link AgentDiscoveryService#searchInstancesByAgentId(String, String)}.
 *
 * <p>Authority: ADR-0160 decision 2 (revised by REQ-2026-004, then
 * REQ-2026-006) + RB6. The ICD routing fields ({@code routeHandle} /
 * {@code health} / {@code contractVersion} / {@code capabilityVersion} /
 * {@code selectionHint = weight + region} / {@code maxConcurrency}) are
 * always populated when a match is found. The business definition fields
 * ({@code agentName} / {@code frameworkType}) are {@link Nullable @Nullable}
 * — {@code searchInstancesByAgentId} populates them for callers that need
 * the full card view. Per HD3-006 the DTO never carries the physical
 * endpoint or {@code routeKey} in plain form — only the opaque
 * {@code routeHandle}; the forwarding layer recovers the endpoint via
 * {@link AgentDiscoveryService#resolveRouteHandle(String, String)}.
 *
 * <p>REQ-2026-004 changes (baseline-breaking):
 * <ul>
 *   <li>Renamed {@code agentType} (String) → {@code frameworkType}
 *       ({@link FrameworkType}).</li>
 *   <li>Discovery collapses to single-value lookup; the dual Method A/B
 *       distinction is removed.</li>
 * </ul>
 *
 * <p>REQ-2026-006 changes (baseline-breaking):
 * <ul>
 *   <li>Added {@code maxConcurrency} field — the 9th routing field, sourced
 *       from {@code RegistryRow.maxConcurrency}. The caller (Orchestrator /
 *       Gateway) uses it for weighted load balancing across N instances of
 *       the same {@code agentId}.</li>
 *   <li>Discovery is now a list lookup
 *       ({@link AgentDiscoveryService#searchInstancesByAgentId}); each
 *       instance gets its own {@code AgentCardDto} with its own
 *       {@code routeHandle}.</li>
 * </ul>
 *
 * <p>Hand-written builder (no Lombok) so the {@code spi.registry} package
 * stays pure Java (ADR-0160 decision 1).
 */
public final class AgentCardDto {

    // ---- ICD routing fields (always populated on match) ----

    private final String serviceId;
    private final String routeHandle;
    private final String health;
    private final String contractVersion;
    private final String capabilityVersion;
    private final int weight;
    private final String region;
    private final int maxConcurrency;

    // ---- Business definition fields (populated by searchInstancesByAgentId) ----

    @Nullable private final String agentName;
    @Nullable private final FrameworkType frameworkType;

    private AgentCardDto(Builder b) {
        this.serviceId = b.serviceId;
        this.routeHandle = b.routeHandle;
        this.health = b.health;
        this.contractVersion = b.contractVersion;
        this.capabilityVersion = b.capabilityVersion;
        this.weight = b.weight;
        this.region = b.region;
        this.maxConcurrency = b.maxConcurrency;
        this.agentName = b.agentName;
        this.frameworkType = b.frameworkType;
    }

    public String getServiceId() {
        return serviceId;
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

    public int getMaxConcurrency() {
        return maxConcurrency;
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
        private String serviceId;
        private String routeHandle;
        private String health;
        private String contractVersion;
        private String capabilityVersion;
        private int weight;
        private String region;
        private int maxConcurrency;
        private String agentName;
        private FrameworkType frameworkType;

        private Builder() {
        }

        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
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

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
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
            Objects.requireNonNull(serviceId, "serviceId");
            Objects.requireNonNull(routeHandle, "routeHandle");
            Objects.requireNonNull(health, "health");
            Objects.requireNonNull(contractVersion, "contractVersion");
            Objects.requireNonNull(capabilityVersion, "capabilityVersion");
            return new AgentCardDto(this);
        }
    }
}
