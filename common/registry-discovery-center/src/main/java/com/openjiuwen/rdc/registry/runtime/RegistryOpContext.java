/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime;

/**
 * Immutable value object carrying the per-operation audit fields consumed by
 * {@link RegistryObservabilityConfig}. Bundles the trace/tenant/agent/version/
 * health/routeHandle (and, for discover, queryDimension/queryValue) so the
 * observability API stays under the parameter-count limit (G.MET.01) without
 * leaking positional argument lists across the controller/scheduler/discovery
 * callers. Null fields are rendered as the audit placeholder ({@code "-"})
 * by the facade.
 *
 * @since 2026-07-10
 */
public final class RegistryOpContext {
    private final String traceId;
    private final String tenantId;
    private final String agentId;
    private final String contractVersion;
    private final String capabilityVersion;
    private final String health;
    private final String routeHandleId;
    private final String queryDimension;
    private final String queryValue;

    private RegistryOpContext(Builder builder) {
        this.traceId = builder.traceId;
        this.tenantId = builder.tenantId;
        this.agentId = builder.agentId;
        this.contractVersion = builder.contractVersion;
        this.capabilityVersion = builder.capabilityVersion;
        this.health = builder.health;
        this.routeHandleId = builder.routeHandleId;
        this.queryDimension = builder.queryDimension;
        this.queryValue = builder.queryValue;
    }

    /**
     * Returns the trace id.
     *
     * @return the trace id bound to this operation
     */
    public String getTraceId() {
        return traceId;
    }

    /**
     * Returns the tenant id.
     *
     * @return the tenant id bound to this operation
     */
    public String getTenantId() {
        return tenantId;
    }

    /**
     * Returns the agent id.
     *
     * @return the agent id, or {@code null} when not applicable (e.g. discover)
     */
    public String getAgentId() {
        return agentId;
    }

    /**
     * Returns the contract version.
     *
     * @return the contract version, or {@code null} when not applicable
     */
    public String getContractVersion() {
        return contractVersion;
    }

    /**
     * Returns the capability version.
     *
     * @return the capability version, or {@code null} when not applicable
     */
    public String getCapabilityVersion() {
        return capabilityVersion;
    }

    /**
     * Returns the health tag.
     *
     * @return the health tag, or {@code null} when not applicable
     */
    public String getHealth() {
        return health;
    }

    /**
     * Returns the route handle id.
     *
     * @return the route handle id, or {@code null} when not applicable
     */
    public String getRouteHandleId() {
        return routeHandleId;
    }

    /**
     * Returns the discover query dimension (agentId / serviceId / capability).
     *
     * @return the query dimension, or {@code null} for non-discover ops
     */
    public String getQueryDimension() {
        return queryDimension;
    }

    /**
     * Returns the discover query value.
     *
     * @return the query value, or {@code null} for non-discover ops
     */
    public String getQueryValue() {
        return queryValue;
    }

    /**
     * Returns a builder seeded with the given traceId/tenantId/agentId.
     *
     * @param traceId the trace id
     * @param tenantId the tenant id
     * @param agentId the agent id
     * @return a new builder
     */
    public static Builder of(String traceId, String tenantId, String agentId) {
        return new Builder(traceId, tenantId, agentId);
    }

    /**
     * Returns a builder seeded with the given traceId/tenantId (no agentId).
     *
     * @param traceId the trace id
     * @param tenantId the tenant id
     * @return a new builder
     */
    public static Builder of(String traceId, String tenantId) {
        return new Builder(traceId, tenantId, null);
    }

    /**
     * Fluent builder for {@link RegistryOpContext}.
     */
    public static final class Builder {
        private final String traceId;
        private final String tenantId;
        private final String agentId;
        private String contractVersion;
        private String capabilityVersion;
        private String health;
        private String routeHandleId;
        private String queryDimension;
        private String queryValue;

        private Builder(String traceId, String tenantId, String agentId) {
            this.traceId = traceId;
            this.tenantId = tenantId;
            this.agentId = agentId;
        }

        /**
         * Sets the contract version.
         *
         * @param contractVersion the contract version
         * @return this builder
         */
        public Builder contractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
            return this;
        }

        /**
         * Sets the capability version.
         *
         * @param capabilityVersion the capability version
         * @return this builder
         */
        public Builder capabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
            return this;
        }

        /**
         * Sets the health tag.
         *
         * @param health the health tag
         * @return this builder
         */
        public Builder health(String health) {
            this.health = health;
            return this;
        }

        /**
         * Sets the route handle id.
         *
         * @param routeHandleId the route handle id
         * @return this builder
         */
        public Builder routeHandleId(String routeHandleId) {
            this.routeHandleId = routeHandleId;
            return this;
        }

        /**
         * Sets the discover query dimension + value.
         *
         * @param dimension the query dimension (agentId / serviceId / capability)
         * @param value the query value
         * @return this builder
         */
        public Builder query(String dimension, String value) {
            this.queryDimension = dimension;
            this.queryValue = value;
            return this;
        }

        /**
         * Builds the immutable context.
         *
         * @return the built context
         */
        public RegistryOpContext build() {
            return new RegistryOpContext(this);
        }
    }
}
