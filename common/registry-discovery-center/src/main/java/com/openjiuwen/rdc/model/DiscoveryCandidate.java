/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Single logical Agent Card discovery candidate (Feat-015 0713
 * {@code AgentCardCandidate}). Candidates are deduplicated by Card identity
 * and version — they do not expose instance identifiers, health, or route
 * references (those belong to FEAT-016).
 *
 * @since 0.1.0 (2026)
 */
public final class DiscoveryCandidate {
    @Nullable
    private final String agentCardJson;
    private final String agentId;
    private final String serviceId;
    @Nullable
    private final String matchedA2aSkillId;
    private final String contractVersion;
    private final String capabilityVersion;
    private final RegistrationStatus registrationStatus;
    private final Freshness freshness;
    @Nullable
    private final Instant lastValidatedAt;

    private DiscoveryCandidate(Builder b) {
        this.agentCardJson = b.agentCardJson;
        this.agentId = Objects.requireNonNull(b.agentId, "agentId");
        this.serviceId = Objects.requireNonNull(b.serviceId, "serviceId");
        this.matchedA2aSkillId = b.matchedA2aSkillId;
        this.contractVersion = Objects.requireNonNull(b.contractVersion, "contractVersion");
        this.capabilityVersion = Objects.requireNonNull(b.capabilityVersion, "capabilityVersion");
        this.registrationStatus = Objects.requireNonNull(b.registrationStatus, "registrationStatus");
        this.freshness = Objects.requireNonNull(b.freshness, "freshness");
        this.lastValidatedAt = b.lastValidatedAt;
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
     * agentCardJson.
     *
     * @return result
     * @since 0.1.0
     */
    @Nullable
    public String agentCardJson() {
        return agentCardJson;
    }
    /**
     * agentId.
     *
     * @return result
     * @since 0.1.0
     */
    public String agentId() {
        return agentId;
    }
    /**
     * serviceId.
     *
     * @return result
     * @since 0.1.0
     */
    public String serviceId() {
        return serviceId;
    }
    /**
     * matchedA2aSkillId.
     *
     * @return result
     * @since 0.1.0
     */
    @Nullable
    public String matchedA2aSkillId() {
        return matchedA2aSkillId;
    }
    /**
     * contractVersion.
     *
     * @return result
     * @since 0.1.0
     */
    public String contractVersion() {
        return contractVersion;
    }
    /**
     * capabilityVersion.
     *
     * @return result
     * @since 0.1.0
     */
    public String capabilityVersion() {
        return capabilityVersion;
    }
    /**
     * registrationStatus.
     *
     * @return result
     * @since 0.1.0
     */
    public RegistrationStatus registrationStatus() {
        return registrationStatus;
    }
    /**
     * freshness.
     *
     * @return result
     * @since 0.1.0
     */
    public Freshness freshness() {
        return freshness;
    }
    /**
     * lastValidatedAt.
     *
     * @return result
     * @since 0.1.0
     */
    @Nullable
    public Instant lastValidatedAt() {
        return lastValidatedAt;
    }
    public static final class Builder {
        private String agentCardJson;
        private String agentId;
        private String serviceId;
        private String matchedA2aSkillId;
        private String contractVersion;
        private String capabilityVersion;
        private RegistrationStatus registrationStatus;
        private Freshness freshness;
        private Instant lastValidatedAt;

        /**
         * agentCardJson.
         *
         * @param agentCardJson agentCardJson
         * @return result
         * @since 0.1.0
         */
        public Builder agentCardJson(String agentCardJson) {
            this.agentCardJson = agentCardJson;
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
         * matchedA2aSkillId.
         *
         * @param matchedA2aSkillId matchedA2aSkillId
         * @return result
         * @since 0.1.0
         */
        public Builder matchedA2aSkillId(String matchedA2aSkillId) {
            this.matchedA2aSkillId = matchedA2aSkillId;
            return this;
        }

        /**
         * contractVersion.
         *
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
         *
         * @param capabilityVersion capabilityVersion
         * @return result
         * @since 0.1.0
         */
        public Builder capabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
            return this;
        }

        /**
         * registrationStatus.
         *
         * @param registrationStatus registrationStatus
         * @return result
         * @since 0.1.0
         */
        public Builder registrationStatus(RegistrationStatus registrationStatus) {
            this.registrationStatus = registrationStatus;
            return this;
        }

        /**
         * freshness.
         *
         * @param freshness freshness
         * @return result
         * @since 0.1.0
         */
        public Builder freshness(Freshness freshness) {
            this.freshness = freshness;
            return this;
        }

        /**
         * lastValidatedAt.
         *
         * @param lastValidatedAt lastValidatedAt
         * @return result
         * @since 0.1.0
         */
        public Builder lastValidatedAt(Instant lastValidatedAt) {
            this.lastValidatedAt = lastValidatedAt;
            return this;
        }

        /**
         * build.
         *
         * @return result
         * @since 0.1.0
         */
        public DiscoveryCandidate build() {
            return new DiscoveryCandidate(this);
        }
    }
}
