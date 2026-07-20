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
 * @since 0.1.0
 */
public final class DiscoveryCandidate {

    @Nullable private final String agentCardJson;
    private final String agentId;
    private final String serviceId;
    @Nullable private final String matchedA2aSkillId;
    private final String contractVersion;
    private final String capabilityVersion;
    private final RegistrationStatus registrationStatus;
    private final Freshness freshness;
    @Nullable private final Instant lastValidatedAt;

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

    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    public String agentCardJson() {
        return agentCardJson;
    }

    public String agentId() {
        return agentId;
    }

    public String serviceId() {
        return serviceId;
    }

    @Nullable
    public String matchedA2aSkillId() {
        return matchedA2aSkillId;
    }

    public String contractVersion() {
        return contractVersion;
    }

    public String capabilityVersion() {
        return capabilityVersion;
    }

    public RegistrationStatus registrationStatus() {
        return registrationStatus;
    }

    public Freshness freshness() {
        return freshness;
    }

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

        public Builder agentCardJson(String agentCardJson) {
            this.agentCardJson = agentCardJson;
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

        public Builder matchedA2aSkillId(String matchedA2aSkillId) {
            this.matchedA2aSkillId = matchedA2aSkillId;
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

        public Builder registrationStatus(RegistrationStatus registrationStatus) {
            this.registrationStatus = registrationStatus;
            return this;
        }

        public Builder freshness(Freshness freshness) {
            this.freshness = freshness;
            return this;
        }

        public Builder lastValidatedAt(Instant lastValidatedAt) {
            this.lastValidatedAt = lastValidatedAt;
            return this;
        }

        public DiscoveryCandidate build() {
            return new DiscoveryCandidate(this);
        }
    }
}
