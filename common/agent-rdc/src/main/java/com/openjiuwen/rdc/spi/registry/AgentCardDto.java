package com.openjiuwen.rdc.spi.registry;

import java.util.Objects;

/**
 * Unified discovery-result DTO returned by both
 * {@link AgentDiscoveryService#discoverBestAgents(String, String, String, int)}
 * (Method A) and
 * {@link AgentDiscoveryService#discoverBestAgents(String, String, String, String, int)}
 * (Method B).
 *
 * <p>Authority: ADR-0160 decision 2 + RB6. The ICD 5 routing fields
 * ({@code routeHandle} / {@code health} / {@code contractVersion} /
 * {@code capabilityVersion} / {@code selectionHint = weight + region}) are
 * always populated by both methods. The business definition fields
 * ({@code agentName} / {@code agentType}) are {@link Nullable @Nullable} —
 * Method A populates them for exploratory callers (Orchestrator / Gateway);
 * Method B leaves them {@code null} for capability-scoped routing. Per
 * HD3-006 the DTO never carries the physical endpoint or {@code routeKey}
 * in plain form — only the opaque {@code routeHandle}; the forwarding layer
 * recovers the endpoint via
 * {@link AgentDiscoveryService#resolveRouteHandle(String, String)}.
 *
 * <p>REQ-2026-001 removed {@code systemProfile} + {@code toolSchemas} —
 * both overlapped with the A2A standard AgentCard that the registry now
 * embeds. Discovery callers that need A2A card details should fetch
 * {@code /.well-known/agent-card.json} directly (the
 * {@link com.openjiuwen.rdc.spi.registry.AgentRegistryEntry#getA2aAgentCard()
 * AgentRegistryEntry.a2aAgentCard} field is not surfaced via this DTO —
 * follow-up PR may add it).
 *
 * <p>Hand-written builder (no Lombok) so the {@code spi.registry} package
 * stays pure Java (ADR-0160 decision 1).
 */
public final class AgentCardDto {

    // ---- ICD 5 routing fields (both methods populate) ----

    private final String routeHandle;
    private final String health;
    private final String contractVersion;
    private final String capabilityVersion;
    private final int weight;
    private final String region;

    // ---- Business definition fields (Method A fills, Method B leaves null) ----

    @Nullable private final String agentName;
    @Nullable private final String agentType;

    private AgentCardDto(Builder b) {
        this.routeHandle = b.routeHandle;
        this.health = b.health;
        this.contractVersion = b.contractVersion;
        this.capabilityVersion = b.capabilityVersion;
        this.weight = b.weight;
        this.region = b.region;
        this.agentName = b.agentName;
        this.agentType = b.agentType;
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
    public String getAgentType() {
        return agentType;
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
        private String agentType;

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

        public Builder agentType(String agentType) {
            this.agentType = agentType;
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
