package com.openjiuwen.rdc.registry.runtime;

import com.openjiuwen.rdc.registry.runtime.api.MvpRegistryController;
import com.openjiuwen.rdc.registry.runtime.discovery.PgMvpDiscoveryServiceImpl;
import com.openjiuwen.rdc.registry.runtime.health.MvpHealthProbeScheduler;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.spi.registry.AgentCard;
import com.openjiuwen.rdc.spi.registry.AgentCardDto;
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
 * End-to-end integration test for the Stage 4 agent registry MVP (RB5).
 *
 * <p>Boots an in-process PostgreSQL (Zonky embedded-postgres) + the full
 * agent-bus Flyway migration, then drives the full registry lifecycle
 * through the production classes:
 * <ol>
 *   <li><b>register</b> — {@link MvpRegistryController#register} (HTTP path)
 *       via {@link JdbcAgentRegistryRepository#upsert}.</li>
 *   <li><b>discover A</b> — Method A natural-language intent discovery
 *       returns rich {@link AgentCardDto}.</li>
 *   <li><b>discover B</b> — Method B capability-scoped discovery returns
 *       minimal {@link AgentCardDto}.</li>
 *   <li><b>resolve</b> — {@code resolveRouteHandle} returns the physical
 *       endpoint for the opaque handle from discover.</li>
 *   <li><b>probe → DEGRADED</b> — {@link MvpHealthProbeScheduler#probeOnlineAgents}
 *       issues {@code GET {endpoint}/health}; a 5xx response
 *       triggers {@code updateStatus(..., "DEGRADED", false)}.</li>
 *   <li><b>DEGRADED visibility</b> — discovery SQL
 *       {@code status IN ('ONLINE','DEGRADED')} keeps the row visible with
 *       {@code health=DEGRADED} (HD3-004).</li>
 *   <li><b>15s visibility window expiry</b> — backdating
 *       {@code last_heartbeat} past the {@code INTERVAL '15 seconds'} window
 *       filters the row out of discovery results.</li>
 *   <li><b>deregister</b> — {@link MvpRegistryController#deregister} (HTTP
 *       path) deletes the row; subsequent discovery returns empty.</li>
 * </ol>
 *
 * <p>Authority: ADR-0160 decisions 2 / 4 / 5 / 6 / 7 + ICD-Agent-Registry-Discovery
 * HD3-001/003/004/005/006 + RB5.
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
        controller = new MvpRegistryController(repository, observability);
        // staleBeforeMs is small (1s) so backdating a row's heartbeat by a
        // few seconds is enough to make it "due" for probe. probe scan limit
        // at 200 matches production.
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

    // ---- full lifecycle --------------------------------------------------

    @Test
    void register_discover_resolve_probe_degrade_visibility_window_deregister() throws Exception {
        String agentEndpoint = agentServer.url("/").toString().replaceAll("/$", "");
        String tenant = "tenant-e2e";
        String agent = "agent-e2e";

        // 1. register
        AgentCard card = sampleCard(tenant, agent, agentEndpoint);
        ResponseEntity<Void> reg = controller.register(card, null, null);
        assertThat(reg.getStatusCode().is2xxSuccessful()).isTrue();

        // 2. discover A — rich DTO. userQuery=null = weight-only ranking per
        //    SPI contract (avoids phraseto_tsquery adjacency requirements).
        List<AgentCardDto> aResults = discovery.discoverBestAgents(
                tenant, null, null, 5);
        assertThat(aResults).hasSize(1);
        AgentCardDto aDto = aResults.get(0);
        assertThat(aDto.getRouteHandle()).isNotBlank();
        assertThat(aDto.getHealth()).isEqualTo("ONLINE");
        assertThat(aDto.getAgentName()).isEqualTo("财务助手");
        assertThat(aDto.getContractVersion()).isEqualTo("1.0.0");

        // 3. discover B — minimal DTO (same row, same handle)
        List<AgentCardDto> bResults = discovery.discoverBestAgents(
                tenant, "cap-billing", null, null, 5);
        assertThat(bResults).hasSize(1);
        AgentCardDto bDto = bResults.get(0);
        assertThat(bDto.getRouteHandle()).isEqualTo(aDto.getRouteHandle());
        assertThat(bDto.getAgentName()).isNull();

        // 4. resolve
        RouteResolution resolution = discovery.resolveRouteHandle(aDto.getRouteHandle(), tenant);
        assertThat(resolution.endpointUrl()).isEqualTo(agentEndpoint);
        assertThat(resolution.routeKey()).isEqualTo("rk://svc/default");
        assertThat(resolution.contractVersion()).isEqualTo("1.0.0");

        // 5. probe → DEGRADED. Stub the agent's health endpoint with 500,
        //    backdate the row's heartbeat so scanDueForProbe picks it up,
        //    then run one scheduler sweep.
        agentServer.enqueue(new MockResponse().setResponseCode(500));
        backdateHeartbeat(tenant, agent, "5 seconds");
        scheduler.probeOnlineAgents();

        // Scheduler does updateStatus(..., DEGRADED, false) on 5xx.
        String statusAfterProbe = readStatus(tenant, agent);
        assertThat(statusAfterProbe)
                .as("HD3-004: 5xx probe → status downgraded to DEGRADED")
                .isEqualTo("DEGRADED");

        // 6. DEGRADED visibility — discovery still sees the row, but health is DEGRADED.
        List<AgentCardDto> degradedResults = discovery.discoverBestAgents(
                tenant, null, null, 5);
        assertThat(degradedResults).hasSize(1);
        assertThat(degradedResults.get(0).getHealth()).isEqualTo("DEGRADED");

        // 7. 15s visibility window expiry — backdate the heartbeat past 15s.
        //    Discovery SQL filters last_heartbeat >= NOW() - INTERVAL '15 seconds'.
        backdateHeartbeat(tenant, agent, "30 seconds");
        List<AgentCardDto> expiredResults = discovery.discoverBestAgents(
                tenant, null, null, 5);
        assertThat(expiredResults)
                .as("HD3-004: row past the 15-second visibility window is filtered out")
                .isEmpty();

        // 8. deregister
        ResponseEntity<Void> dereg = controller.deregister(tenant, agent, null, null);
        assertThat(dereg.getStatusCode().is2xxSuccessful()).isTrue();

        // 9. discovery returns empty after deregister
        List<AgentCardDto> postDereg = discovery.discoverBestAgents(
                tenant, null, null, 5);
        assertThat(postDereg).isEmpty();

        // 10. resolve after deregister → entry_not_found
        assertThatThrownBy(() -> discovery.resolveRouteHandle(aDto.getRouteHandle(), tenant))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("entry_not_found");
    }

    // ---- tenant isolation in E2E ----------------------------------------

    @Test
    void cross_tenant_discovery_returns_empty_and_resolve_rejects() throws Exception {
        String agentEndpoint = agentServer.url("/").toString().replaceAll("/$", "");

        // Register an agent under tenant-A.
        controller.register(sampleCard("tenant-A", "agent-x", agentEndpoint), null, null);

        // tenant-B discovery must not see tenant-A's row.
        List<AgentCardDto> crossTenant = discovery.discoverBestAgents(
                "tenant-B", null, null, 5);
        assertThat(crossTenant)
                .as("HD3-003: cross-tenant discovery must return empty (application-layer WHERE)")
                .isEmpty();

        // Obtain a real handle via the SPI (tenant-A discovery returns the row
        // registered above). RouteHandleCodec is package-private to
        // registry.runtime.discovery (PR #389 #5) — callers outside that
        // package MUST go through the SPI to obtain handles, never by direct
        // encode, so the codec's encoding format can evolve without breaking
        // cross-module consumers.
        List<AgentCardDto> aResults = discovery.discoverBestAgents(
                "tenant-A", null, null, 5);
        assertThat(aResults).hasSize(1);
        String handle = aResults.get(0).getRouteHandle();

        // tenant-B caller must be rejected when trying to resolve tenant-A's handle.
        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-B"))
                .isInstanceOf(com.openjiuwen.rdc.spi.registry.TenantIsolationViolationException.class);
    }

    // ---- #9: websearch_to_tsquery keyword-style recall -------------------

    /**
     * PR #389 #9: a userQuery with a different word order from the indexed
     * keywords MUST still match (keyword-style OR semantics). Under the old
     * {@code phraseto_tsquery} this returned 0 results because phrase
     * adjacency was required.
     */
    @Test
    void discover_with_reordered_user_query_still_matches_via_websearch_tsquery() throws Exception {
        String agentEndpoint = agentServer.url("/").toString().replaceAll("/$", "");
        // capability_keywords = "billing invoice" (weight A source).
        controller.register(sampleCard("tenant-tsquery", "agent-tsquery", agentEndpoint), null, null);

        // userQuery with reordered words — must still match under
        // websearch_to_tsquery (would NOT match under phraseto_tsquery).
        List<AgentCardDto> results = discovery.discoverBestAgents(
                "tenant-tsquery", "invoice billing", null, 5);
        assertThat(results)
                .as("PR #389 #9: websearch_to_tsquery matches keywords regardless "
                    + "of order (was: phraseto_tsquery required adjacency → 0 results)")
                .hasSize(1);
        assertThat(results.get(0).getRouteHandle()).isNotBlank();
        assertThat(results.get(0).getAgentName()).isEqualTo("财务助手");
    }

    // ---- helpers ---------------------------------------------------------

    private static AgentCard sampleCard(String tenant, String agent, String endpoint) {
        AgentCard card = new AgentCard();
        card.setTenantId(tenant);
        card.setAgentId(agent);
        card.setServiceId("svc-e2e");
        card.setAgentName("财务助手");
        card.setAgentType("assistant");
        card.setCapability("cap-billing");
        card.setCapabilityKeywords("billing invoice");
        card.setSystemProfile("profile-e2e");
        card.setRouteKey("rk://svc/default");
        card.setContractVersion("1.0.0");
        card.setCapabilityVersion("2.1.0");
        card.setEndpointUrl(endpoint);
        card.setMaxConcurrency(10);
        card.setWeight(100);
        card.setRegion("cn-east-1");
        card.setToolSchemas("[]");
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
}
