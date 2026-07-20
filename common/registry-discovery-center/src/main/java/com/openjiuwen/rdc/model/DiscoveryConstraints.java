/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.util.Set;

/**
 * Hard constraints for {@link DiscoveryQuery} (Feat-015 0711 scope §5.1.6).
 *
 * @since 0.1.0
 */
public final class DiscoveryConstraints {

    @Nullable
    private final String contractVersion;
    @Nullable
    private final String capabilityVersion;
    private final Set<String> requiredSkillTags;
    private final Set<String> requiredCapabilities;
    private final Set<String> requiredInputModes;
    private final Set<String> requiredOutputModes;
    private final Set<String> requiredSecuritySchemes;
    @Nullable
    private final HealthRequirement healthRequirement;

    private DiscoveryConstraints(Builder b) {
        this.contractVersion = b.contractVersion;
        this.capabilityVersion = b.capabilityVersion;
        this.requiredSkillTags = copyOrEmpty(b.requiredSkillTags);
        this.requiredCapabilities = copyOrEmpty(b.requiredCapabilities);
        this.requiredInputModes = copyOrEmpty(b.requiredInputModes);
        this.requiredOutputModes = copyOrEmpty(b.requiredOutputModes);
        this.requiredSecuritySchemes = copyOrEmpty(b.requiredSecuritySchemes);
        this.healthRequirement = b.healthRequirement;
    }

    private static Set<String> copyOrEmpty(Set<String> values) {
        return values == null ? Set.of() : Set.copyOf(values);
    }

    public static DiscoveryConstraints none() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    @Nullable
    public String contractVersion() {
        return contractVersion;
    }

    @Nullable
    public String capabilityVersion() {
        return capabilityVersion;
    }

    public Set<String> requiredSkillTags() {
        return requiredSkillTags;
    }

    public Set<String> requiredCapabilities() {
        return requiredCapabilities;
    }

    public Set<String> requiredInputModes() {
        return requiredInputModes;
    }

    public Set<String> requiredOutputModes() {
        return requiredOutputModes;
    }

    public Set<String> requiredSecuritySchemes() {
        return requiredSecuritySchemes;
    }

    public HealthRequirement healthRequirement() {
        return healthRequirement == null ? HealthRequirement.HEALTHY_OR_DEGRADED : healthRequirement;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof DiscoveryConstraints other)) {
            return false;
        }
        return java.util.Objects.equals(contractVersion, other.contractVersion)
                && java.util.Objects.equals(capabilityVersion, other.capabilityVersion)
                && requiredSkillTags.equals(other.requiredSkillTags)
                && requiredCapabilities.equals(other.requiredCapabilities)
                && requiredInputModes.equals(other.requiredInputModes)
                && requiredOutputModes.equals(other.requiredOutputModes)
                && requiredSecuritySchemes.equals(other.requiredSecuritySchemes)
                && healthRequirement() == other.healthRequirement();
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(
                contractVersion,
                capabilityVersion,
                requiredSkillTags,
                requiredCapabilities,
                requiredInputModes,
                requiredOutputModes,
                requiredSecuritySchemes,
                healthRequirement());
    }

    public static final class Builder {
        private String contractVersion;
        private String capabilityVersion;
        private Set<String> requiredSkillTags;
        private Set<String> requiredCapabilities;
        private Set<String> requiredInputModes;
        private Set<String> requiredOutputModes;
        private Set<String> requiredSecuritySchemes;
        private HealthRequirement healthRequirement;

        public Builder contractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
            return this;
        }

        public Builder capabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
            return this;
        }

        public Builder requiredSkillTags(Set<String> requiredSkillTags) {
            this.requiredSkillTags = requiredSkillTags;
            return this;
        }

        public Builder requiredCapabilities(Set<String> requiredCapabilities) {
            this.requiredCapabilities = requiredCapabilities;
            return this;
        }

        public Builder requiredInputModes(Set<String> requiredInputModes) {
            this.requiredInputModes = requiredInputModes;
            return this;
        }

        public Builder requiredOutputModes(Set<String> requiredOutputModes) {
            this.requiredOutputModes = requiredOutputModes;
            return this;
        }

        public Builder requiredSecuritySchemes(Set<String> requiredSecuritySchemes) {
            this.requiredSecuritySchemes = requiredSecuritySchemes;
            return this;
        }

        public Builder healthRequirement(HealthRequirement healthRequirement) {
            this.healthRequirement = healthRequirement;
            return this;
        }

        public DiscoveryConstraints build() {
            return new DiscoveryConstraints(this);
        }
    }
}
