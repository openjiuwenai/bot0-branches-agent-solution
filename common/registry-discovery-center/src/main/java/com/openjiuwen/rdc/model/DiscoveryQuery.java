/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.util.Objects;

/**
 * Structured discovery request (Feat-015 0711 scope §3 {@code DiscoveryQuery}).
 *
 * @since 0.1.0 (2026)
 */
public final class DiscoveryQuery {
    private final RegistryRequestContext context;
    @Nullable
    private final String agentId;
    @Nullable
    private final String serviceId;
    @Nullable
    private final String a2aSkillId;
    private final DiscoveryConstraints constraints;
    private final int limit;
    @Nullable
    private final String continuationToken;

    private DiscoveryQuery(Builder b) {
        this.context = Objects.requireNonNull(b.context, "context");
        this.agentId = b.agentId;
        this.serviceId = b.serviceId;
        this.a2aSkillId = b.a2aSkillId;
        this.constraints = b.constraints == null ? DiscoveryConstraints.none() : b.constraints;
        this.limit = b.limit <= 0 ? 20 : b.limit;
        this.continuationToken = b.continuationToken;
    }

    /**
     * builder.
     *
     * @return result
     * @since 0.1.0
     */
    public static Builder builder() {
        return new Builder();
    }
    /**
     * validate.
     *
     * @since 0.1.0
     */
    public void validate() {
        context.validate();
        if (isBlank(agentId) && isBlank(serviceId) && isBlank(a2aSkillId)) {
            throw new InvalidDiscoveryQueryException(
                    "INVALID_QUERY",
                    "at least one of agentId, serviceId, or a2aSkillId is required");
        }
        if (limit <= 0 || limit > 200) {
            throw new InvalidDiscoveryQueryException(
                    "INVALID_QUERY", "limit must be between 1 and 200");
        }
    }

    /**
     * context.
     *
     * @return result
     * @since 0.1.0
     */
    public RegistryRequestContext context() {
        return context;
    }
    /**
     * agentId.
     *
     * @return result
     * @since 0.1.0
     */
    @Nullable
    public String agentId() {
        return agentId;
    }
    /**
     * serviceId.
     *
     * @return result
     * @since 0.1.0
     */
    @Nullable
    public String serviceId() {
        return serviceId;
    }
    /**
     * a2aSkillId.
     *
     * @return result
     * @since 0.1.0
     */
    @Nullable
    public String a2aSkillId() {
        return a2aSkillId;
    }
    /**
     * constraints.
     *
     * @return result
     * @since 0.1.0
     */
    public DiscoveryConstraints constraints() {
        return constraints;
    }
    /**
     * limit.
     *
     * @return result
     * @since 0.1.0
     */
    public int limit() {
        return limit;
    }
    /**
     * continuationToken.
     *
     * @return result
     * @since 0.1.0
     */
    @Nullable
    public String continuationToken() {
        return continuationToken;
    }
    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }
    public static final class Builder {
        private RegistryRequestContext context;
        private String agentId;
        private String serviceId;
        private String a2aSkillId;
        private DiscoveryConstraints constraints;
        private int limit = 20;
        private String continuationToken;

        /**
         * context.
         *
         * @param context context
         * @return result
         * @since 0.1.0
         */
        public Builder context(RegistryRequestContext context) {
            this.context = context;
            return this;
        }

        /**
         * agentId.
         *
         * @param agentId agentId
         * @return result
         * @since 0.1.0
         */
        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        /**
         * serviceId.
         *
         * @param serviceId serviceId
         * @return result
         * @since 0.1.0
         */
        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        /**
         * a2aSkillId.
         *
         * @param a2aSkillId a2aSkillId
         * @return result
         * @since 0.1.0
         */
        public Builder a2aSkillId(String a2aSkillId) {
            this.a2aSkillId = a2aSkillId;
            return this;
        }

        /**
         * constraints.
         *
         * @param constraints constraints
         * @return result
         * @since 0.1.0
         */
        public Builder constraints(DiscoveryConstraints constraints) {
            this.constraints = constraints;
            return this;
        }

        /**
         * limit.
         *
         * @param limit limit
         * @return result
         * @since 0.1.0
         */
        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        /**
         * continuationToken.
         *
         * @param continuationToken continuationToken
         * @return result
         * @since 0.1.0
         */
        public Builder continuationToken(String continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        /**
         * build.
         *
         * @return result
         * @since 0.1.0
         */
        public DiscoveryQuery build() {
            return new DiscoveryQuery(this);
        }
    }
}
