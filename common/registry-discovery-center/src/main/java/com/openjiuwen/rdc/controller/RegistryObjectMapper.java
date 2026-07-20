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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openjiuwen.rdc.model.AgentCardCandidate;
import com.openjiuwen.rdc.model.DiscoveryCandidate;

/**
 * Shared Jackson 2 {@link ObjectMapper} factory for registry runtime code paths
 * that still target the Jackson 2 API ({@code MvpRegistryController} A2A jsonb
 * serialisation, integration tests). HTTP responses use Spring Boot 4's
 * auto-configured Jackson 3 {@code JsonMapper} with the same
 * {@link DiscoveryCandidateJacksonMixin} via {@link RegistryJacksonConfig}.
 */
public final class RegistryObjectMapper {

    private RegistryObjectMapper() {
    }

    /**
     * Jackson 2 mapper with {@link DiscoveryCandidate} mixin and JSR-310 support.
     *
     * @return a configured, non-null {@link ObjectMapper}
     */
    public static ObjectMapper createJackson2() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.addMixIn(DiscoveryCandidate.class, DiscoveryCandidateJacksonMixin.class);
        mapper.addMixIn(AgentCardCandidate.class, DiscoveryCandidateJacksonMixin.class);
        return mapper;
    }
}
