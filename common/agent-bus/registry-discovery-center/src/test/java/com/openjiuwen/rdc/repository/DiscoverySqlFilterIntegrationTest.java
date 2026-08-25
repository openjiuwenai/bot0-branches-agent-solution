/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.service.PgMvpDiscoveryServiceImpl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import javax.sql.DataSource;

/**
 * FEAT-016 discovery SQL filter integration tests (Feat-Func-016 L2 §4.4).
 */
class DiscoverySqlFilterIntegrationTest {
    private static final String TENANT = "tenant-filter";
    private static final String AGENT = "agent-filter";
    private static final String SERVICE = "inst-filter";

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
    static void shutDown() {
    }

    @BeforeEach
    void clean() {
        new JdbcTemplate(dataSource).execute("DELETE FROM agent_registry_mvp");
    }

    @Test
    void draining_instance_excluded_from_runtime_search() {
        registerOnlineRow();
        jdbc().update(
                "UPDATE agent_registry_mvp SET status = 'DRAINING', lifecycle_status = 'DRAINING' "
                        + "WHERE tenant_id = ? AND agent_id = ?",
                TENANT, AGENT);

        assertThat(discovery.searchInstancesByAgentId(TENANT, AGENT)).isEmpty();
    }

    @Test
    void stale_heartbeat_beyond_15s_excluded() {
        registerOnlineRow();
        jdbc().update(
                "UPDATE agent_registry_mvp SET last_heartbeat = NOW() - INTERVAL '16 seconds' "
                        + "WHERE tenant_id = ? AND agent_id = ?",
                TENANT, AGENT);

        assertThat(discovery.searchInstancesByAgentId(TENANT, AGENT)).isEmpty();
    }

    @Test
    void degraded_within_15s_still_visible() {
        registerOnlineRow();
        jdbc().update(
                "UPDATE agent_registry_mvp SET status = 'DEGRADED', "
                        + "last_heartbeat = NOW() - INTERVAL '10 seconds' "
                        + "WHERE tenant_id = ? AND agent_id = ?",
                TENANT, AGENT);

        List<AgentCardDto> result = discovery.searchInstancesByAgentId(TENANT, AGENT);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHealth()).isEqualTo("DEGRADED");
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private static void registerOnlineRow() {
        AgentRegistryEntry entry = new AgentRegistryEntry();
        entry.setTenantId(TENANT);
        entry.setAgentId(AGENT);
        entry.setAgentName("demo");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        entry.setRouteKey("/v1/query");
        entry.setContractVersion("1.0.0");
        entry.setCapabilityVersion("1.0.0");
        entry.setEndpointUrl("http://127.0.0.1:8090");
        entry.setMaxConcurrency(10);
        entry.setWeight(100);
        com.openjiuwen.rdc.model.ServiceIdCodec.applyTo(entry);
        com.openjiuwen.rdc.model.InstanceIdCodec.applyTo(entry);
        repository.upsert(entry, cardJson());
        jdbc().update(
                "UPDATE agent_registry_mvp SET service_id = ?, instance_id = ?, lifecycle_status = 'ACTIVE', "
                        + "effective_health = 'HEALTHY', lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' "
                        + "WHERE tenant_id = ? AND agent_id = ?",
                SERVICE, SERVICE, TENANT, AGENT);
    }

    private static String cardJson() {
        return "{"
                + "\"name\":\"demo\",\"description\":\"d\",\"version\":\"1.0.0\","
                + "\"defaultInputModes\":[\"text\"],\"defaultOutputModes\":[\"text\"],"
                + "\"capabilities\":{\"streaming\":true},"
                + "\"skills\":[],"
                + "\"supportedInterfaces\":[{\"protocol\":\"jsonrpc\",\"url\":\"/a2a\"}]"
                + "}";
    }
}
