/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.controller.MvpRegistryController;
import com.openjiuwen.rdc.controller.RegistryObjectMapper;
import com.openjiuwen.rdc.model.AgentCardDiscoveryQuery;
import com.openjiuwen.rdc.model.AgentCardDiscoveryResult;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.DiscoveryConstraints;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.DiscoveryResult;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.RegistryRequestContext;
import com.openjiuwen.rdc.repository.EmbeddedPostgresTestSupport;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Integration tests for Feat-015 0713 structured {@code DiscoverAgentCards}.
 */
class StructuredDiscoveryIntegrationTest {

    private static DataSource dataSource;
    private static JdbcAgentRegistryRepository repository;
    private static PgMvpDiscoveryServiceImpl discovery;
    private static MvpRegistryController controller;

    @BeforeAll
    static void bootStack() throws Exception {
        dataSource = EmbeddedPostgresTestSupport.sharedDataSource();

        repository = new JdbcAgentRegistryRepository(dataSource);
        RegistryObservabilityConfig observability = new RegistryObservabilityConfig(new SimpleMeterRegistry());
        discovery = new PgMvpDiscoveryServiceImpl(
                repository,
                new com.openjiuwen.rdc.tenant.ThreadLocalTenantContext(),
                observability,
                null);
        controller = new MvpRegistryController(
                repository, discovery, observability, RegistryObjectMapper.createJackson2(),
                new com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties());
    }

    @BeforeEach
    void cleanTable() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM agent_card_source_ref");
        jdbc.execute("DELETE FROM agent_card_registration");
        jdbc.execute("DELETE FROM agent_registry_mvp");
    }

    @Test
    void discover_by_agent_id_returns_success_with_candidate() {
        registerSample("tenant-d", "agent-d", skillJson("refund-skill", Set.of("finance")));

        DiscoveryResult result = discover("tenant-d", "agent-d", null, null, null);

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).agentId()).isEqualTo("agent-d");
        assertThat(result.candidates().get(0).serviceId()).isNotBlank();
    }

    @Test
    void discover_by_a2a_skill_id_filters_candidates() {
        registerSample("tenant-d", "agent-d", skillJson("refund-skill", Set.of("finance")));

        DiscoveryResult result = discover("tenant-d", null, null, "refund-skill", null);

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates().get(0).matchedA2aSkillId()).isEqualTo("refund-skill");
    }

    @Test
    void discover_unknown_agent_returns_no_match() {
        DiscoveryResult result = discover("tenant-d", "missing-agent", null, null, null);
        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.NO_MATCH);
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void discover_version_mismatch_returns_version_unavailable() {
        registerSample("tenant-d", "agent-d", skillJson("refund-skill", Set.of("finance")));

        DiscoveryConstraints constraints = DiscoveryConstraints.builder()
                .contractVersion("9.9.9")
                .build();
        DiscoveryResult result = discoverWithConstraints("tenant-d", "agent-d", constraints);

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.VERSION_UNAVAILABLE);
    }

    @Test
    void discover_skill_tag_mismatch_unavailable() {
        registerSample("tenant-d", "agent-d", skillJson("refund-skill", Set.of("finance")));

        DiscoveryConstraints constraints = DiscoveryConstraints.builder()
                .requiredSkillTags(Set.of("nonexistent-tag"))
                .build();
        DiscoveryResult result = discoverWithConstraints("tenant-d", "agent-d", constraints);

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.CONSTRAINT_UNAVAILABLE);
    }

    @Test
    void http_discover_endpoint_returns_structured_result() {
        registerSample("tenant-d", "agent-http", skillJson("pay-skill", Set.of("billing")));

        MvpRegistryController.DiscoverRequest request = new MvpRegistryController.DiscoverRequest(
                new MvpRegistryController.ContextRequest("tenant-d", "test-client", "req-1",
                        Instant.now().plusSeconds(30)),
                "agent-http",
                null,
                null,
                null,
                10,
                null);

        AgentCardDiscoveryResult result = controller.discover(request, null, null, null);

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates()).isNotEmpty();
    }

    private static AgentCardDiscoveryResult discoverAgentCards(String tenantId, String agentId, String serviceId,
                                            String skillId, DiscoveryConstraints constraints) {
        RegistryRequestContext ctx = new RegistryRequestContext(
                tenantId, "test", "trace-1", "req-1", Instant.now().plusSeconds(30));
        var builder = AgentCardDiscoveryQuery.builder().context(ctx).limit(10);
        if (agentId != null) {
            builder.agentId(agentId);
        }
        if (serviceId != null) {
            builder.serviceId(serviceId);
        }
        if (skillId != null) {
            builder.a2aSkillId(skillId);
        }
        if (constraints != null) {
            builder.constraints(constraints);
        }
        return discovery.discoverAgentCards(builder.build());
    }

    private static com.openjiuwen.rdc.model.DiscoveryResult discover(String tenantId, String agentId, String serviceId,
                                            String skillId, DiscoveryConstraints constraints) {
        return discoverAgentCards(tenantId, agentId, serviceId, skillId, constraints).toDiscoveryResult();
    }

    private static DiscoveryResult discoverWithConstraints(String tenantId, String agentId,
                                                           DiscoveryConstraints constraints) {
        return discover(tenantId, agentId, null, null, constraints);
    }

    private static void registerSample(String tenant, String agent, String a2aJson) {
        AgentRegistryEntry entry = new AgentRegistryEntry();
        entry.setTenantId(tenant);
        entry.setAgentId(agent);
        entry.setAgentName("demo-agent");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        entry.setRouteKey("/v1/query");
        entry.setContractVersion("1.0.0");
        entry.setCapabilityVersion("1.0.0");
        entry.setEndpointUrl("http://127.0.0.1:8090");
        entry.setMaxConcurrency(10);
        entry.setWeight(100);
        com.openjiuwen.rdc.model.ServiceIdCodec.applyTo(entry);
        com.openjiuwen.rdc.model.InstanceIdCodec.applyTo(entry);
        repository.upsert(entry, a2aJson);
    }

    private static String skillJson(String skillId, Set<String> tags) {
        String tagsJson = tags.stream()
                .map(t -> "\"" + t + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return "{"
                + "\"name\":\"demo-agent\","
                + "\"description\":\"demo\","
                + "\"version\":\"1.0.0\","
                + "\"defaultInputModes\":[\"text\"],"
                + "\"defaultOutputModes\":[\"text\"],"
                + "\"capabilities\":{\"streaming\":true},"
                + "\"skills\":[{\"id\":\"" + skillId + "\",\"name\":\"skill\","
                + "\"description\":\"d\",\"tags\":[" + tagsJson + "]}],"
                + "\"capabilities\":{\"streaming\":true},"
                + "\"supportedInterfaces\":[{\"protocol\":\"jsonrpc\",\"url\":\"/a2a\"}]"
                + "}";
    }
}
