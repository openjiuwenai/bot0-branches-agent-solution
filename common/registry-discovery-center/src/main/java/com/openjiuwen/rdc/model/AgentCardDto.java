/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

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
 *
 * @since 0.1.0
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

    @Nullable
    private final String agentName;
    @Nullable
    private final FrameworkType frameworkType;

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

    /**
     * getServiceId.
     * @return result
     * @since 0.1.0
     */
    public String getServiceId() {
        return serviceId;
    }

    /**
     * getRouteHandle.
     * @return result
     * @since 0.1.0
     */
    public String getRouteHandle() {
        return routeHandle;
    }

    /**
     * getHealth.
     * @return result
     * @since 0.1.0
     */
    public String getHealth() {
        return health;
    }

    /**
     * getContractVersion.
     * @return result
     * @since 0.1.0
     */
    public String getContractVersion() {
        return contractVersion;
    }

    /**
     * getCapabilityVersion.
     * @return result
     * @since 0.1.0
     */
    public String getCapabilityVersion() {
        return capabilityVersion;
    }

    /**
     * getWeight.
     * @return result
     * @since 0.1.0
     */
    public int getWeight() {
        return weight;
    }

    /**
     * getRegion.
     * @return result
     * @since 0.1.0
     */
    public String getRegion() {
        return region;
    }

    /**
     * getMaxConcurrency.
     * @return result
     * @since 0.1.0
     */
    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    @Nullable
    /**
     * getAgentName.
     * @return result
     * @since 0.1.0
     */
    public String getAgentName() {
        return agentName;
    }

    @Nullable
    /**
     * getFrameworkType.
     * @return result
     * @since 0.1.0
     */
    public FrameworkType getFrameworkType() {
        return frameworkType;
    }

    /**
     * builder.
     * @return result
     * @since 0.1.0
     */
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

        /**
         * serviceId.
         * @param serviceId serviceId
         * @return result
         * @since 0.1.0
         */
        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        /**
         * routeHandle.
         * @param routeHandle routeHandle
         * @return result
         * @since 0.1.0
         */
        public Builder routeHandle(String routeHandle) {
            this.routeHandle = routeHandle;
            return this;
        }

        /**
         * health.
         * @param health health
         * @return result
         * @since 0.1.0
         */
        public Builder health(String health) {
            this.health = health;
            return this;
        }

        /**
         * contractVersion.
         * @param contractVersion contractVersion
         * @return result
         * @since 0.1.0
         */
        public Builder contractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
            return this;
        }

        /**
         * capabilityVersion.
         * @param capabilityVersion capabilityVersion
         * @return result
         * @since 0.1.0
         */
        public Builder capabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
            return this;
        }

        /**
         * weight.
         * @param weight weight
         * @return result
         * @since 0.1.0
         */
        public Builder weight(int weight) {
            this.weight = weight;
            return this;
        }

        /**
         * region.
         * @param region region
         * @return result
         * @since 0.1.0
         */
        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /**
         * maxConcurrency.
         * @param maxConcurrency maxConcurrency
         * @return result
         * @since 0.1.0
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * agentName.
         * @param agentName agentName
         * @return result
         * @since 0.1.0
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * frameworkType.
         * @param frameworkType frameworkType
         * @return result
         * @since 0.1.0
         */
        public Builder frameworkType(FrameworkType frameworkType) {
            this.frameworkType = frameworkType;
            return this;
        }

        /**
         * build.
         * @return result
         * @since 0.1.0
         */
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
