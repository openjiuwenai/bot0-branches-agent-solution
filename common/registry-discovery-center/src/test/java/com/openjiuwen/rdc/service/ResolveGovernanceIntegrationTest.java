package com.openjiuwen.rdc.service;

import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.repository.EmbeddedPostgresTestSupport;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.EntryNotFoundException;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.LeaseExpiredException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResolveGovernanceIntegrationTest {

    private static DataSource dataSource;
    private static JdbcAgentRegistryRepository repository;
    private static PgMvpDiscoveryServiceImpl discovery;

    private static final String TENANT = "tenant-res";
    private static final String AGENT = "agent-res";
    private static final String SERVICE = "inst-res";

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
        new JdbcTemplate(dataSource).execute("DELETE FROM agent_registry_mvp");
    }

    @Test
    void resolve_active_instance_succeeds() {
        registerRow("ACTIVE", "HEALTHY");
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(TENANT, AGENT, SERVICE, SERVICE, "/v1/query", "1.0.0"));

        var resolution = discovery.resolveRouteHandle(handle, TENANT, "gateway", "trace-1");

        assertThat(resolution.endpointUrl()).isEqualTo("http://127.0.0.1:8090");
        assertThat(resolution.capabilityVersion()).isEqualTo("1.0.0");
        assertThat(resolution.contractVersion()).isEqualTo("1.0.0");
    }

    @Test
    void resolve_draining_instance_not_found() {
        registerRow("DRAINING", "HEALTHY");
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(TENANT, AGENT, SERVICE, SERVICE, "/v1/query", "1.0.0"));

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, TENANT, "gateway", "trace-1"))
                .isInstanceOf(EntryNotFoundException.class);
    }

    @Test
    void resolve_expired_lease_throws_lease_expired() {
        registerRow("ACTIVE", "HEALTHY");
        new JdbcTemplate(dataSource).update(
                "UPDATE agent_registry_mvp SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' "
                        + "WHERE tenant_id = ? AND agent_id = ? AND service_id = ?",
                TENANT, AGENT, SERVICE);

        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(TENANT, AGENT, SERVICE, SERVICE, "/v1/query", "1.0.0"));

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, TENANT, "gateway", "trace-1"))
                .isInstanceOf(LeaseExpiredException.class);
    }

    private void registerRow(String lifecycle, String health) {
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
        new JdbcTemplate(dataSource).update(
                "UPDATE agent_registry_mvp SET service_id = ?, instance_id = ?, lifecycle_status = ?, "
                        + "effective_health = ?, lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 hour' "
                        + "WHERE tenant_id = ? AND agent_id = ?",
                SERVICE, SERVICE, lifecycle, health, TENANT, AGENT);
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
