/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.openjiuwen.rdc.model.Freshness;
import com.openjiuwen.rdc.model.RegistrationStatus;

import java.time.Instant;

/**
 * Jackson mixin for logical discovery candidates ({@code agentId()}-style
 * accessors on {@link com.openjiuwen.rdc.model.DiscoveryCandidate} and
 * {@link com.openjiuwen.rdc.model.AgentCardCandidate}).
 */
abstract class DiscoveryCandidateJacksonMixin {

    @JsonProperty
    abstract String agentCardJson();

    @JsonProperty
    abstract String agentId();

    @JsonProperty
    abstract String serviceId();

    @JsonProperty
    abstract String matchedA2aSkillId();

    @JsonProperty
    abstract String contractVersion();

    @JsonProperty
    abstract String capabilityVersion();

    @JsonProperty
    abstract RegistrationStatus registrationStatus();

    @JsonProperty
    abstract Freshness freshness();

    @JsonProperty
    abstract Instant lastValidatedAt();
}
