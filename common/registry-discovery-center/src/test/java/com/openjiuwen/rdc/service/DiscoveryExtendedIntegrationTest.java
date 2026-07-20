/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.CallerNotAuthorizedException;
import com.openjiuwen.rdc.model.DiscoveryConstraints;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.HealthRequirement;
import com.openjiuwen.rdc.model.InvalidDiscoveryQueryException;
import com.openjiuwen.rdc.model.RegistryRequestContext;
import com.openjiuwen.rdc.repository.EmbeddedPostgresTestSupport;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.security.CallerAuthorizationPolicy;
import com.openjiuwen.rdc.security.RegistrySecurityProperties;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Set;

import javax.sql.DataSource;

class DiscoveryExtendedIntegrationTest {

    private static final AtomicInteger NEXT_PORT = new AtomicInteger(18000);

    private static DataSource dataSource;
    private static JdbcAgentRegistryRepository repository;
    private static PgMvpDiscoveryServiceImpl discovery;

    @BeforeAll
    static void bootStack() throws Exception {
        dataSource = EmbeddedPostgresTestSupport.sharedDataSource();
        repository = new JdbcAgentRegistryRepository(dataSource);
        discovery = new PgMvpDiscoveryServiceImpl(
                repository,
                new com.openjiuwen.rdc.tenant.ThreadLocalTenantContext(),
                new RegistryObservabilityConfig(new SimpleMeterRegistry()),
                null);
    }

    @AfterAll
    static void shutDown() throws Exception {
    }

    @BeforeEach
    void clean() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM agent_card_source_ref");
        jdbc.execute("DELETE FROM agent_card_registration");
        jdbc.execute("DELETE FROM agent_registry_mvp");
    }

    @Test
    void discover_by_deployment_service_id_maps_to_agent_id() {
        registerActive("tenant-x", "deploy-svc", cardJson(true, "skill-1", Set.of("a")));

        RegistryRequestContext ctx = context("tenant-x", "client");
        var result = discovery.discover(DiscoveryQuery.builder()
                .context(ctx)
                .serviceId("deploy-svc")
                .limit(10)
                .build());

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void draining_instance_returns_no_match() {
        registerActive("tenant-x", "agent-x", cardJson(true, "s1", Set.of()));
        new JdbcTemplate(dataSource).update(
                "UPDATE agent_card_registration SET registration_status = 'REMOVED' "
                        + "WHERE tenant_id = 'tenant-x' AND agent_id = 'agent-x'");

        var result = discovery.discover(DiscoveryQuery.builder()
                .context(context("tenant-x", "client"))
                .agentId("agent-x")
                .limit(10)
                .build());

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.NO_MATCH);
    }

    @Test
    void unhealthy_instance_still_discoverable() {
        registerActive("tenant-x", "agent-x", cardJson(true, "s1", Set.of()));
        new JdbcTemplate(dataSource).update(
                "UPDATE agent_registry_mvp SET effective_health = 'UNHEALTHY' "
                        + "WHERE tenant_id = 'tenant-x' AND agent_id = 'agent-x'");

        var result = discovery.discover(DiscoveryQuery.builder()
                .context(context("tenant-x", "client"))
                .agentId("agent-x")
                .constraints(DiscoveryConstraints.builder()
                        .healthRequirement(HealthRequirement.HEALTHY)
                        .build())
                .limit(10)
                .build());

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
    }

    @Test
    void required_capabilities_filters_candidates() {
        registerActive("tenant-x", "agent-cap", cardJson(true, "s1", Set.of()));
        registerActive("tenant-x", "agent-cap", cardJson(false, "s1", Set.of()));

        var result = discovery.discover(DiscoveryQuery.builder()
                .context(context("tenant-x", "client"))
                .agentId("agent-cap")
                .constraints(DiscoveryConstraints.builder()
                        .requiredCapabilities(Set.of("streaming"))
                        .build())
                .limit(10)
                .build());

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void continuation_token_pages_candidates() {
        registerActiveWithVersion("tenant-x", "page-agent", "1.0.0");
        registerActiveWithVersion("tenant-x", "page-agent", "2.0.0");

        RegistryRequestContext ctx = context("tenant-x", "pager");
        DiscoveryQuery firstQuery = DiscoveryQuery.builder()
                .context(ctx)
                .agentId("page-agent")
                .limit(1)
                .build();

        var first = discovery.discover(firstQuery);
        assertThat(first.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(first.candidates()).hasSize(1);
        assertThat(first.nextToken()).isNotBlank();

        var second = discovery.discover(DiscoveryQuery.builder()
                .context(ctx)
                .agentId("page-agent")
                .limit(1)
                .continuationToken(first.nextToken())
                .build());
        assertThat(second.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(second.candidates()).hasSize(1);
    }

    @Test
    void mismatched_continuation_token_rejected() {
        registerActive("tenant-x", "page-agent", cardJson(true, "s1", Set.of()));

        RegistryRequestContext ctx = context("tenant-x", "pager");
        DiscoveryQuery original = DiscoveryQuery.builder()
                .context(ctx)
                .agentId("page-agent")
                .limit(1)
                .build();
        String tokenForOtherQuery = ContinuationTokenCodec.encode(original, 1);

        DiscoveryQuery query = DiscoveryQuery.builder()
                .context(ctx)
                .agentId("other-agent")
                .limit(1)
                .continuationToken(tokenForOtherQuery)
                .build();

        assertThatThrownBy(() -> discovery.discover(query))
                .isInstanceOf(InvalidDiscoveryQueryException.class)
                .hasMessageContaining("continuation token");
    }

    @Test
    void capability_version_filter_matching_only() {
        registerActiveWithVersion("tenant-x", "multi-agent", "1.0.0");
        registerActiveWithVersion("tenant-x", "multi-agent", "2.0.0");

        var all = discovery.discover(DiscoveryQuery.builder()
                .context(context("tenant-x", "client"))
                .agentId("multi-agent")
                .limit(10)
                .build());
        assertThat(all.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(all.candidates()).hasSize(2);

        var v2Only = discovery.discover(DiscoveryQuery.builder()
                .context(context("tenant-x", "client"))
                .agentId("multi-agent")
                .constraints(DiscoveryConstraints.builder()
                        .capabilityVersion("2.0.0")
                        .build())
                .limit(10)
                .build());
        assertThat(v2Only.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(v2Only.candidates()).hasSize(1);
        assertThat(v2Only.candidates().get(0).capabilityVersion()).isEqualTo("2.0.0");

        var missing = discovery.discover(DiscoveryQuery.builder()
                .context(context("tenant-x", "client"))
                .agentId("multi-agent")
                .constraints(DiscoveryConstraints.builder()
                        .capabilityVersion("9.9.9")
                        .build())
                .limit(10)
                .build());
        assertThat(missing.outcome()).isEqualTo(DiscoveryOutcome.VERSION_UNAVAILABLE);
    }

    @Test
    void caller_allowlist_rejects_unauthorized_caller() {
        RegistrySecurityProperties props = new RegistrySecurityProperties();
        props.getCallerAllowlist().put("tenant-x", Set.of("gateway"));
        PgMvpDiscoveryServiceImpl guarded = new PgMvpDiscoveryServiceImpl(
                repository,
                new com.openjiuwen.rdc.tenant.ThreadLocalTenantContext(),
                new RegistryObservabilityConfig(new SimpleMeterRegistry()),
                new CallerAuthorizationPolicy.Allowlist(props));

        registerActive("tenant-x", "agent-x", cardJson(true, "s1", Set.of()));

        assertThatThrownBy(() -> guarded.discover(DiscoveryQuery.builder()
                .context(context("tenant-x", "unknown-caller"))
                .agentId("agent-x")
                .limit(10)
                .build())).isInstanceOf(CallerNotAuthorizedException.class);
    }

    private static RegistryRequestContext context(String tenantId, String callerRef) {
        return new RegistryRequestContext(
                tenantId, callerRef, "trace-1", "req-1", Instant.now().plusSeconds(30));
    }

    private void registerActiveWithVersion(String tenant, String agent, String capabilityVersion) {
        int port = NEXT_PORT.incrementAndGet();
        AgentRegistryEntry entry = new AgentRegistryEntry();
        entry.setTenantId(tenant);
        entry.setAgentId(agent);
        entry.setAgentName("demo");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        entry.setRouteKey("/v1/query");
        entry.setContractVersion("1.0.0");
        entry.setCapabilityVersion(capabilityVersion);
        entry.setEndpointUrl("http://127.0.0.1:" + port);
        entry.setMaxConcurrency(10);
        entry.setWeight(100);
        com.openjiuwen.rdc.model.ServiceIdCodec.applyTo(entry);
        com.openjiuwen.rdc.model.InstanceIdCodec.applyTo(entry);
        String card = cardJson(true, "s-" + capabilityVersion, Set.of());
        repository.upsert(entry, card);
        new JdbcTemplate(dataSource).update(
                "UPDATE agent_registry_mvp SET deployment_service_id = ?, "
                        + "lifecycle_status = 'ACTIVE', effective_health = 'HEALTHY', "
                        + "freshness = 'FRESH', lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' "
                        + "WHERE tenant_id = ? AND agent_id = ? AND service_id = ? AND instance_id = ?",
                agent, tenant, agent, entry.getServiceId(), entry.getInstanceId());
    }

    private void registerActive(String tenant, String agent, String cardJson) {
        int port = NEXT_PORT.incrementAndGet();
        AgentRegistryEntry entry = new AgentRegistryEntry();
        entry.setTenantId(tenant);
        entry.setAgentId(agent);
        entry.setAgentName("demo");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        entry.setRouteKey("/v1/query");
        entry.setContractVersion("1.0.0");
        entry.setCapabilityVersion("1.0.0");
        entry.setEndpointUrl("http://127.0.0.1:" + port);
        entry.setMaxConcurrency(10);
        entry.setWeight(100);
        com.openjiuwen.rdc.model.ServiceIdCodec.applyTo(entry);
        com.openjiuwen.rdc.model.InstanceIdCodec.applyTo(entry);
        repository.upsert(entry, cardJson);
        new JdbcTemplate(dataSource).update(
                "UPDATE agent_registry_mvp SET deployment_service_id = ?, "
                        + "lifecycle_status = 'ACTIVE', effective_health = 'HEALTHY', freshness = 'FRESH', "
                        + "lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' "
                        + "WHERE tenant_id = ? AND agent_id = ? AND service_id = ? AND instance_id = ?",
                agent, tenant, agent, entry.getServiceId(), entry.getInstanceId());
    }

    private static String cardJson(boolean streaming, String skillId, Set<String> tags) {
        String tagsJson = tags.isEmpty() ? "" : tags.stream().map(t -> "\"" + t + "\"").reduce((a, b) -> a + "," + b)
                .orElse("");
        return "{"
                + "\"name\":\"demo\",\"description\":\"d\",\"version\":\"1.0.0\","
                + "\"defaultInputModes\":[\"text\"],\"defaultOutputModes\":[\"text\"],"
                + "\"capabilities\":{\"streaming\":" + streaming + "},"
                + "\"skills\":[{\"id\":\"" + skillId + "\",\"name\":\"n\",\"description\":\"d\",\"tags\":[" + tagsJson + "]}],"
                + "\"supportedInterfaces\":[{\"protocol\":\"jsonrpc\",\"url\":\"/a2a\"}]"
                + "}";
    }
}
