package com.openjiuwen.rdc.registry.runtime;

import com.openjiuwen.rdc.registry.runtime.api.MvpRegistryController;
import com.openjiuwen.rdc.registry.runtime.discovery.PgMvpDiscoveryServiceImpl;
import com.openjiuwen.rdc.registry.runtime.health.MvpHealthProbeScheduler;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.spi.registry.AgentRegistryEntry;
import com.openjiuwen.rdc.spi.registry.AgentCardDto;
import com.openjiuwen.rdc.spi.registry.FrameworkType;
import com.openjiuwen.rdc.spi.registry.RouteResolution;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test for the Stage 4 agent registry MVP (RB5),
 * revised for REQ-2026-006 (multi-instance list lookup).
 *
 * <p>Boots an in-process PostgreSQL (Zonky embedded-postgres) + the full
 * agent-rdc Flyway migration (V2→V3→V4→V5), then drives the full registry
 * lifecycle through the production classes:
 * <ol>
 *   <li><b>register</b> — {@link MvpRegistryController#register} (HTTP path)
 *       via {@link JdbcAgentRegistryRepository#upsert}.</li>
 *   <li><b>searchInstancesByAgentId</b> — list lookup returns rich
 *       {@link AgentCardDto} entries with all fields populated (one entry
 *       per matching instance; each carries its own opaque routeHandle).</li>
 *   <li><b>resolve</b> — {@code resolveRouteHandle} returns the physical
 *       endpoint for the opaque handle from searchInstancesByAgentId.</li>
 *   <li><b>probe → DEGRADED</b> — {@link MvpHealthProbeScheduler#probeOnlineAgents}
 *       issues {@code GET {endpoint}/health}; a 5xx response
 *       triggers {@code updateStatus(..., "DEGRADED", false)}.</li>
 *   <li><b>DEGRADED visibility</b> — searchInstancesByAgentId still returns
 *       the row with {@code health=DEGRADED} (status IN ONLINE,DEGRADED).</li>
 *   <li><b>deregister</b> — {@link MvpRegistryController#deregister} (HTTP
 *       path) deletes all instances for the pair; subsequent
 *       searchInstancesByAgentId returns an empty list.</li>
 * </ol>
 *
 * <p>REQ-2026-006: discovery collapses from single-value Optional lookup to
 * list lookup. The 15s visibility window at discovery layer is gone; visibility
 * window applies only to the health-probe scheduler scan.
 */
class AgentRegistryEndToEndIntegrationTest {

    private static EmbeddedPostgres pg;
    private static DataSource dataSource;
    private static MockWebServer agentServer;

    private static JdbcAgentRegistryRepository repository;
    private static RegistryObservabilityConfig observability;
    private static PgMvpDiscoveryServiceImpl discovery;
    private static MvpRegistryController controller;
    private static MvpHealthProbeScheduler scheduler;

    @BeforeAll
    static void bootStack() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        dataSource = pg.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();

        agentServer = new MockWebServer();
        agentServer.start();

        repository = new JdbcAgentRegistryRepository(dataSource);
        observability = new RegistryObservabilityConfig(new SimpleMeterRegistry());
        discovery = new PgMvpDiscoveryServiceImpl(
                repository,
                new com.openjiuwen.rdc.registry.runtime.tenant.ThreadLocalTenantContext(),
                observability);
        controller = new MvpRegistryController(
                repository, discovery, observability, new com.fasterxml.jackson.databind.ObjectMapper());
        scheduler = new MvpHealthProbeScheduler(
                repository, observability,
                /* staleBeforeMs = */ 1_000L,
                /* scanLimit = */ 200);
    }

    @AfterAll
    static void shutDown() throws Exception {
        if (agentServer != null) {
            agentServer.shutdown();
        }
        if (pg != null) {
            pg.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource)
                .execute("DELETE FROM agent_registry_mvp");
    }

    @Test
    void register_search_resolve_probe_degrade_deregister() throws Exception {
        String agentEndpoint = agentServer.url("/").toString().replaceAll("/$", "");
        String tenant = "tenant-e2e";
        String agent = "agent-e2e";

        // 1. register
        AgentRegistryEntry card = sampleCard(tenant, agent, agentEndpoint);
        ResponseEntity<Void> reg = controller.register(card, null, null);
        assertThat(reg.getStatusCode().is2xxSuccessful()).isTrue();

        // 2. searchInstancesByAgentId — rich DTO with all fields populated.
        List<AgentCardDto> result = discovery.searchInstancesByAgentId(tenant, agent);
        assertThat(result).hasSize(1);
        AgentCardDto dto = result.get(0);
        assertThat(dto.getRouteHandle()).isNotBlank();
        assertThat(dto.getHealth()).isEqualTo("ONLINE");
        assertThat(dto.getAgentName()).isEqualTo("财务助手");
        assertThat(dto.getFrameworkType()).isEqualTo(FrameworkType.JIUWEN);
        assertThat(dto.getContractVersion()).isEqualTo("1.0.0");
        assertThat(dto.getCapabilityVersion()).isEqualTo("2.1.0");
        assertThat(dto.getWeight()).isEqualTo(100);
        assertThat(dto.getRegion()).isEqualTo("cn-east-1");

        // 3. resolve
        RouteResolution resolution = discovery.resolveRouteHandle(dto.getRouteHandle(), tenant);
        assertThat(resolution.endpointUrl()).isEqualTo(agentEndpoint);
        assertThat(resolution.routeKey()).isEqualTo("rk://svc/default");
        assertThat(resolution.contractVersion()).isEqualTo("1.0.0");

        // 4. probe → DEGRADED. Stub the agent's health endpoint with 500,
        //    backdate the row's heartbeat so scanDueForProbe picks it up,
        //    then run one scheduler sweep.
        agentServer.enqueue(new MockResponse().setResponseCode(500));
        backdateHeartbeat(tenant, agent, "5 seconds");
        scheduler.probeOnlineAgents();

        String statusAfterProbe = readStatus(tenant, agent);
        assertThat(statusAfterProbe)
                .as("HD3-004: 5xx probe → status downgraded to DEGRADED")
                .isEqualTo("DEGRADED");

        // 5. DEGRADED visibility — searchInstancesByAgentId still returns the row.
        List<AgentCardDto> degraded = discovery.searchInstancesByAgentId(tenant, agent);
        assertThat(degraded).hasSize(1);
        assertThat(degraded.get(0).getHealth()).isEqualTo("DEGRADED");

        // 6. deregister (deletes ALL instances for the pair)
        ResponseEntity<Void> dereg = controller.deregister(tenant, agent, null, null);
        assertThat(dereg.getStatusCode().is2xxSuccessful()).isTrue();

        // 7. searchInstancesByAgentId returns empty list after deregister
        assertThat(discovery.searchInstancesByAgentId(tenant, agent)).isEmpty();

        // 8. resolve after deregister → entry_not_found
        assertThatThrownBy(() -> discovery.resolveRouteHandle(dto.getRouteHandle(), tenant))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("entry_not_found");
    }

    @Test
    void cross_tenant_search_returns_empty_and_resolve_rejects() throws Exception {
        String agentEndpoint = agentServer.url("/").toString().replaceAll("/$", "");

        controller.register(sampleCard("tenant-A", "agent-x", agentEndpoint), null, null);

        // tenant-B search must not see tenant-A's row.
        assertThat(discovery.searchInstancesByAgentId("tenant-B", "agent-x"))
                .as("HD3-003: cross-tenant search returns empty list")
                .isEmpty();

        // Obtain a real handle via tenant-A search.
        List<AgentCardDto> aResult = discovery.searchInstancesByAgentId("tenant-A", "agent-x");
        assertThat(aResult).hasSize(1);
        String handle = aResult.get(0).getRouteHandle();

        // tenant-B caller must be rejected when resolving tenant-A's handle.
        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-B"))
                .isInstanceOf(com.openjiuwen.rdc.spi.registry.TenantIsolationViolationException.class);
    }

    @Test
    void register_applies_defaults_when_request_body_omits_max_concurrency_and_weight() throws Exception {
        String agentEndpoint = agentServer.url("/").toString().replaceAll("/$", "");
        String tenant = "tenant-defaults";
        String agent = "agent-defaults";

        AgentRegistryEntry card = new AgentRegistryEntry();
        card.setTenantId(tenant);
        card.setAgentId(agent);
        card.setAgentName("defaults-helper");
        card.setFrameworkType(FrameworkType.JIUWEN);
        card.setRouteKey("rk://svc/default");
        card.setContractVersion("1.0.0");
        card.setCapabilityVersion("2.1.0");
        card.setEndpointUrl(agentEndpoint);
        // maxConcurrency + weight intentionally omitted — controller must
        // apply defaults so the NOT NULL columns are satisfied.

        ResponseEntity<Void> reg = controller.register(card, null, null);
        assertThat(reg.getStatusCode().is2xxSuccessful()).isTrue();

        Integer maxConcurrency = readInt("max_concurrency", tenant, agent);
        Integer weight = readInt("weight", tenant, agent);
        assertThat(maxConcurrency)
                .as("push path must default max_concurrency to 10 when request body omits it")
                .isEqualTo(10);
        assertThat(weight)
                .as("push path must default weight to 100 when request body omits it")
                .isEqualTo(100);
    }

    // ---- helpers ---------------------------------------------------------

    private static AgentRegistryEntry sampleCard(String tenant, String agent, String endpoint) {
        AgentRegistryEntry card = new AgentRegistryEntry();
        card.setTenantId(tenant);
        card.setAgentId(agent);
        card.setAgentName("财务助手");
        card.setFrameworkType(FrameworkType.JIUWEN);
        card.setRouteKey("rk://svc/default");
        card.setContractVersion("1.0.0");
        card.setCapabilityVersion("2.1.0");
        card.setEndpointUrl(endpoint);
        card.setMaxConcurrency(10);
        card.setWeight(100);
        card.setRegion("cn-east-1");
        card.setA2aAgentCard(org.a2aproject.sdk.spec.AgentCard.builder()
                .name("财务助手")
                .description("billing invoice helper")
                .version("1.0.0")
                .capabilities(new org.a2aproject.sdk.spec.AgentCapabilities(
                        false, false, false, java.util.List.of()))
                .defaultInputModes(java.util.List.of())
                .defaultOutputModes(java.util.List.of())
                .skills(java.util.List.of())
                .supportedInterfaces(java.util.List.of())
                .build());
        return card;
    }

    private static void backdateHeartbeat(String tenant, String agent, String interval) {
        new org.springframework.jdbc.core.JdbcTemplate(dataSource).update(
                "UPDATE agent_registry_mvp SET last_heartbeat = NOW() - INTERVAL '" + interval + "' "
                + "WHERE tenant_id = ? AND agent_id = ?", tenant, agent);
    }

    private static String readStatus(String tenant, String agent) {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                "SELECT status FROM agent_registry_mvp WHERE tenant_id = ? AND agent_id = ?",
                String.class, tenant, agent);
    }

    private static Integer readInt(String column, String tenant, String agent) {
        return new org.springframework.jdbc.core.JdbcTemplate(dataSource).queryForObject(
                "SELECT " + column + " FROM agent_registry_mvp WHERE tenant_id = ? AND agent_id = ?",
                Integer.class, tenant, agent);
    }
}
