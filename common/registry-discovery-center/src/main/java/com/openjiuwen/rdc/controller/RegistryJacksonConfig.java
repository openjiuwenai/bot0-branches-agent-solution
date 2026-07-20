/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import com.openjiuwen.rdc.model.AgentCardCandidate;
import com.openjiuwen.rdc.model.DiscoveryCandidate;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link DiscoveryCandidateJacksonMixin} on Spring Boot 4's
 * auto-configured Jackson 3 {@code JsonMapper} used for HTTP responses.
 *
 * @since 0.1.0 (2026)
  */
@Configuration
public class RegistryJacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer discoveryCandidateJsonMixin() {
        return builder -> builder
                .addMixIn(DiscoveryCandidate.class, DiscoveryCandidateJacksonMixin.class)
                // POST /discover returns AgentCardCandidate, not DiscoveryCandidate.
                .addMixIn(AgentCardCandidate.class, DiscoveryCandidateJacksonMixin.class);
    }
}
