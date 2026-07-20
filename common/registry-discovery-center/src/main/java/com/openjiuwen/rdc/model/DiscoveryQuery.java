/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.util.Objects;

/**
 * Structured discovery request (Feat-015 0711 scope §3 {@code DiscoveryQuery}).
 *
 * @since 0.1.0
 */
public final class DiscoveryQuery {

    private final RegistryRequestContext context;
    @Nullable private final String agentId;
    @Nullable private final String serviceId;
    @Nullable private final String a2aSkillId;
    private final DiscoveryConstraints constraints;
    private final int limit;
    @Nullable private final String continuationToken;

    private DiscoveryQuery(Builder b) {
        this.context = Objects.requireNonNull(b.context, "context");
        this.agentId = b.agentId;
        this.serviceId = b.serviceId;
        this.a2aSkillId = b.a2aSkillId;
        this.constraints = b.constraints == null ? DiscoveryConstraints.none() : b.constraints;
        this.limit = b.limit <= 0 ? 20 : b.limit;
        this.continuationToken = b.continuationToken;
    }

    public static Builder builder() {
        return new Builder();
    }

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

    public RegistryRequestContext context() {
        return context;
    }

    @Nullable
    public String agentId() {
        return agentId;
    }

    @Nullable
    public String serviceId() {
        return serviceId;
    }

    @Nullable
    public String a2aSkillId() {
        return a2aSkillId;
    }

    public DiscoveryConstraints constraints() {
        return constraints;
    }

    public int limit() {
        return limit;
    }

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

        public Builder context(RegistryRequestContext context) {
            this.context = context;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public Builder serviceId(String serviceId) {
            this.serviceId = serviceId;
            return this;
        }

        public Builder a2aSkillId(String a2aSkillId) {
            this.a2aSkillId = a2aSkillId;
            return this;
        }

        public Builder constraints(DiscoveryConstraints constraints) {
            this.constraints = constraints;
            return this;
        }

        public Builder limit(int limit) {
            this.limit = limit;
            return this;
        }

        public Builder continuationToken(String continuationToken) {
            this.continuationToken = continuationToken;
            return this;
        }

        public DiscoveryQuery build() {
            return new DiscoveryQuery(this);
        }
    }
}
