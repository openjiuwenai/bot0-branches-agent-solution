/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.openjiuwen.rdc.model.AgentCardCandidate;
import com.openjiuwen.rdc.model.AgentCardDiscoveryResult;
import com.openjiuwen.rdc.model.DiscoveryCandidate;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.DiscoveryResult;
import com.openjiuwen.rdc.model.Freshness;
import com.openjiuwen.rdc.model.RegistrationStatus;

import tools.jackson.databind.json.JsonMapper;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

/**
 * Guards {@code POST /discover} JSON shape for Feat-015 0713 logical candidates.
 *
 * @since 0.1.0 (2026)
 */
class DiscoveryCandidateJsonSerializationTest {
    private static final DiscoveryResult SAMPLE_RESULT = new DiscoveryResult(
            DiscoveryOutcome.SUCCESS,
            List.of(DiscoveryCandidate.builder()
                    .agentId("billing-svc")
                    .serviceId("billing-svc")
                    .contractVersion("1.0.0")
                    .capabilityVersion("1.0.0")
                    .registrationStatus(RegistrationStatus.REGISTERED)
                    .freshness(Freshness.FRESH)
                    .matchedA2aSkillId("billing-skill")
                    .lastValidatedAt(Instant.parse("2026-07-11T10:00:00Z"))
                    .build()),
            null,
            "trace-1");

    @Test
    void bare_jackson2_mapper_serializes_candidate_as_empty_object() throws Exception {
        ObjectMapper bare = new ObjectMapper();
        bare.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        String json = bare.writeValueAsString(SAMPLE_RESULT.candidates().get(0));
        assertThat(json).isEqualTo("{}");
    }

    @Test
    void registry_jackson2_serializes_discovery_result() throws Exception {
        String json = RegistryObjectMapper.createJackson2().writeValueAsString(SAMPLE_RESULT);

        assertThat(json).contains("\"agentId\":\"billing-svc\"");
        assertThat(json).contains("\"serviceId\":\"billing-svc\"");
        assertThat(json).contains("\"contractVersion\":\"1.0.0\"");
        assertThat(json).contains("\"capabilityVersion\":\"1.0.0\"");
        assertThat(json).contains("\"matchedA2aSkillId\":\"billing-skill\"");
        assertThat(json).contains("\"registrationStatus\":\"REGISTERED\"");
        assertThat(json).contains("\"freshness\":\"FRESH\"");
        assertThat(json).contains("\"outcome\":\"SUCCESS\"");
        assertThat(json).doesNotContain("routeHandle");
    }

    @Test
    void jackson3_mixin_serializes_discovery_result() throws Exception {
        JsonMapper mapper = JsonMapper.builder()
                .addMixIn(DiscoveryCandidate.class, DiscoveryCandidateJacksonMixin.class)
                .build();

        String json = mapper.writeValueAsString(SAMPLE_RESULT);

        assertThat(json).contains("\"agentId\":\"billing-svc\"");
        assertThat(json).contains("\"contractVersion\":\"1.0.0\"");
        assertThat(json).contains("\"outcome\":\"SUCCESS\"");
        assertThat(json).doesNotContain("\"candidates\":[{}]");
        assertThat(json).doesNotContain("routeHandle");
    }

    @Test
    void bare_jackson2_serializes_candidate_empty() throws Exception {
        AgentCardCandidate candidate = AgentCardCandidate.from(SAMPLE_RESULT.candidates().get(0));
        ObjectMapper bare = new ObjectMapper();
        bare.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        String json = bare.writeValueAsString(candidate);
        assertThat(json).isEqualTo("{}");
    }

    @Test
    void registry_jackson2_serializes_card_discovery() throws Exception {
        AgentCardDiscoveryResult result = AgentCardDiscoveryResult.from(SAMPLE_RESULT);
        String json = RegistryObjectMapper.createJackson2().writeValueAsString(result);

        assertThat(json).contains("\"agentId\":\"billing-svc\"");
        assertThat(json).contains("\"serviceId\":\"billing-svc\"");
        assertThat(json).contains("\"registrationStatus\":\"REGISTERED\"");
        assertThat(json).contains("\"freshness\":\"FRESH\"");
        assertThat(json).contains("\"outcome\":\"SUCCESS\"");
        assertThat(json).doesNotContain("\"candidates\":[{}]");
        assertThat(json).doesNotContain("routeHandle");
    }

    @Test
    void jackson3_mixin_serializes_card_discovery() throws Exception {
        AgentCardDiscoveryResult result = AgentCardDiscoveryResult.from(SAMPLE_RESULT);
        JsonMapper mapper = JsonMapper.builder()
                .addMixIn(DiscoveryCandidate.class, DiscoveryCandidateJacksonMixin.class)
                .addMixIn(AgentCardCandidate.class, DiscoveryCandidateJacksonMixin.class)
                .build();

        String json = mapper.writeValueAsString(result);

        assertThat(json).contains("\"agentId\":\"billing-svc\"");
        assertThat(json).contains("\"capabilityVersion\":\"1.0.0\"");
        assertThat(json).contains("\"outcome\":\"SUCCESS\"");
        assertThat(json).doesNotContain("\"candidates\":[{}]");
    }
}
