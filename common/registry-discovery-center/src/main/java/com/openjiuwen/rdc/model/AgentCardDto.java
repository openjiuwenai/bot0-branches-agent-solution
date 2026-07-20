/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.util.Objects;

/**
 * Discovery-result DTO returned by
 * {@link AgentDiscoveryService#searchInstancesByAgentId(String, String, String)}.
 *
 * <p>Authority: ADR-0160 decision 2 (revised by REQ-2026-004, then
 * REQ-2026-006, then FEAT-016) + RB6. The ICD routing fields ({@code serviceId}
 * / {@code routeHandle} / {@code health} / {@code contractVersion} /
 * {@code capabilityVersion} / {@code selectionHint = weight + region} /
 * {@code maxConcurrency}) are always populated when a match is found. The
 * business definition fields ({@code agentName} / {@code frameworkType}) are
 * {@link Nullable @Nullable} — {@code searchInstancesByAgentId} populates them
 * for callers that need the full card view. Per HD3-006 the DTO never carries
 * the physical endpoint or {@code routeKey} in plain form — only the opaque
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
 * <p>FEAT-016 阶段一 changes (baseline-breaking):
 * <ul>
 *   <li>Added {@code serviceId} field — the logical service identifier
 *       (host only, caller-overridable). Visible in the agent/client
 *       projection layer per L2 §2.3.2 so callers can group instances by
 *       logical service. Sourced from {@code RegistryRow.serviceId}.</li>
 * </ul>
 *
 * <p>Hand-written builder (no Lombok) so the {@code spi.registry} package
 * stays pure Java (ADR-0160 decision 1).
 *
 * @since 2026-07-10
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
     * Logical service identifier (host only, caller-overridable). FEAT-016
     * adds this field to the agent/client projection layer per L2 §2.3.2 —
     * callers can group instances by logical service.
     *
     * @return the logical service identifier (never {@code null})
     */
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

    /**
     * Create a new fluent {@link Builder} for an {@link AgentCardDto}.
     *
     * @return a fresh builder (never {@code null})
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Hand-written fluent builder for {@link AgentCardDto}. Mandatory fields
     * are checked in {@link #build()} via {@link Objects#requireNonNull}.
     *
     * @since 2026-07-10
     */
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
         * Set the logical service identifier.
         *
         * @param serviceId logical service identifier (host only, caller-overridable)
         * @return this builder for chaining
         */
        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        /**
         * Set the opaque route handle produced by {@code RouteHandleCodec}.
         *
         * @param routeHandle opaque route handle (v2: 6-field)
         * @return this builder for chaining
         */
        public Builder routeHandle(String routeHandle) {
            this.routeHandle = routeHandle;
            return this;
        }

        /**
         * Set the lifecycle health state.
         *
         * @param health one of {@code ONLINE}/{@code DEGRADED}/{@code DRAINING}
         * @return this builder for chaining
         */
        public Builder health(String health) {
            this.health = health;
            return this;
        }

        /**
         * Set the contract version pinned at registration.
         *
         * @param contractVersion contract version string
         * @return this builder for chaining
         */
        public Builder contractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
            return this;
        }

        /**
         * Set the capability version pinned at registration.
         *
         * @param capabilityVersion capability version string
         * @return this builder for chaining
         */
        public Builder capabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
            return this;
        }

        /**
         * Set the selection weight (higher = preferred).
         *
         * @param weight selection weight
         * @return this builder for chaining
         */
        public Builder weight(int weight) {
            this.weight = weight;
            return this;
        }

        /**
         * Set the deployment region tag.
         *
         * @param region region tag (e.g. {@code cn-east-1})
         * @return this builder for chaining
         */
        public Builder region(String region) {
            this.region = region;
            return this;
        }

        /**
         * Set the max in-flight concurrency the agent accepts.
         *
         * @param maxConcurrency max concurrency
         * @return this builder for chaining
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Set the agent display name (business definition field).
         *
         * @param agentName agent display name; {@code null} when not populated
         * @return this builder for chaining
         */
        public Builder agentName(String agentName) {
            this.agentName = agentName;
            return this;
        }

        /**
         * Set the runtime framework type (business definition field).
         *
         * @param frameworkType framework type; {@code null} when not populated
         * @return this builder for chaining
         */
        public Builder frameworkType(FrameworkType frameworkType) {
            this.frameworkType = frameworkType;
            return this;
        }

        /**
         * Build the immutable {@link AgentCardDto}.
         *
         * @return a new immutable DTO (never {@code null})
         * @throws NullPointerException if any mandatory routing field is {@code null}
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
