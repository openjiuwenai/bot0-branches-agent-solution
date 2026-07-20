/*
 * Copyright (C) 2026 Huawei Technologies Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
