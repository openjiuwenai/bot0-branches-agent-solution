package com.openjiuwen.rdc.reconcile;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider;
import com.openjiuwen.rdc.service.PgMvpDiscoveryServiceImpl;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.repository.EmbeddedPostgresTestSupport;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.model.AgentIdCodec;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.HealthRequirement;
import com.openjiuwen.rdc.model.RegistryRequestContext;
import com.openjiuwen.rdc.model.DiscoveryConstraints;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation lifecycle semantics: PENDING, DEGRADED refresh, digest skip, revision, lease.
 */
class ReconciliationLifecycleIntegrationTest {

    private static DataSource dataSource;
    private static MockWebServer runtimeServer;
    private static JdbcAgentRegistryRepository repository;
    private static PgMvpDiscoveryServiceImpl discovery;

    private static final String TENANT = "tenant-life";
    private static final String SERVICE_ID = "life-svc";
    private static final String INSTANCE_ID = "life-svc-pod-0";

    @BeforeAll
    static void bootStack() throws Exception {
        dataSource = EmbeddedPostgresTestSupport.sharedDataSource();

        runtimeServer = new MockWebServer();
        runtimeServer.start();

        repository = new JdbcAgentRegistryRepository(dataSource);
        discovery = new PgMvpDiscoveryServiceImpl(
                repository,
                new com.openjiuwen.rdc.tenant.ThreadLocalTenantContext(),
                new RegistryObservabilityConfig(new SimpleMeterRegistry()),
                null);
    }

    @AfterAll
    static void shutDown() throws Exception {
        if (runtimeServer != null) {
            runtimeServer.shutdown();
        }
    }

    @BeforeEach
    void clean() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM agent_card_source_ref");
        jdbc.execute("DELETE FROM agent_card_registration");
        jdbc.execute("DELETE FROM agent_registry_mvp");
        jdbc.execute("DELETE FROM registry_source_state");
        restartRuntimeServer();
    }

    private static void restartRuntimeServer() throws Exception {
        if (runtimeServer != null) {
            try {
                runtimeServer.shutdown();
            } catch (Exception ignored) {
                // Best-effort isolation between tests sharing the static server.
            }
        }
        runtimeServer = new MockWebServer();
        runtimeServer.start();
    }

    @Test
    void first_fetch_failure_writes_pending_and_excludes_from_discovery() {
        runtimeServer.enqueue(new MockResponse().setResponseCode(404));

        ReconciliationService service = reconciliationService();
        service.reconcile(provider(baseUrl(), Readiness.READY));

        String lifecycle = readLifecycle();
        assertThat(lifecycle).isEqualTo("PENDING");

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        var result = discover(agentId, HealthRequirement.HEALTHY_OR_DEGRADED);
        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.NO_MATCH);
    }

    @Test
    void refresh_failure_marks_degraded_but_retains_card_snapshot() throws Exception {
        runtimeServer.enqueue(validCardResponse());
        ReconciliationService service = reconciliationService();
        StaticDeploymentDiscoveryProvider provider = provider(baseUrl(), Readiness.READY);
        service.reconcile(provider);

        String cardBefore = new JdbcTemplate(dataSource).queryForObject(
                "SELECT a2a_agent_card::text FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, INSTANCE_ID);
        assertThat(cardBefore).contains("life-agent");

        runtimeServer.enqueue(new MockResponse().setResponseCode(500));
        ReconciliationService.ReconciliationResult second = service.reconcile(provider);
        assertThat(second.success()).isTrue();

        assertThat(readEffectiveHealth()).isEqualTo("DEGRADED");
        String cardAfter = new JdbcTemplate(dataSource).queryForObject(
                "SELECT a2a_agent_card::text FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, INSTANCE_ID);
        assertThat(cardAfter).isEqualTo(cardBefore);

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        assertThat(discover(agentId, HealthRequirement.HEALTHY).outcome())
                .isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(discover(agentId, HealthRequirement.HEALTHY_OR_DEGRADED).outcome())
                .isEqualTo(DiscoveryOutcome.SUCCESS);
    }

    @Test
    void unchanged_card_digest_skips_second_write() throws Exception {
        runtimeServer.enqueue(validCardResponse());
        runtimeServer.enqueue(validCardResponse());
        ReconciliationService service = reconciliationService();
        StaticDeploymentDiscoveryProvider provider = provider(baseUrl(), Readiness.READY);

        ReconciliationService.ReconciliationResult first = service.reconcile(provider);
        assertThat(first.created() + first.updated()).isGreaterThanOrEqualTo(1);

        ReconciliationService.ReconciliationResult second = service.reconcile(provider);
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
    }

    @Test
    void stale_source_revision_is_skipped() throws Exception {
        runtimeServer.enqueue(validCardResponse());
        ReconciliationService service = reconciliationService();
        service.reconcile(provider(baseUrl(), Readiness.READY));

        new JdbcTemplate(dataSource).update(
                "UPDATE registry_source_state SET last_processed_revision = 999 WHERE source_id = ?",
                StaticDeploymentDiscoveryProvider.SOURCE_ID);

        ReconciliationService.ReconciliationResult stale = service.reconcile(provider(baseUrl(), Readiness.READY));
        assertThat(stale.success()).isTrue();
        assertThat(stale.created()).isZero();
        assertThat(stale.updated()).isZero();
    }

    @Test
    void expired_lease_removed_on_reconcile() throws Exception {
        runtimeServer.enqueue(validCardResponse());
        ReconciliationService service = reconciliationService();
        StaticDeploymentDiscoveryProvider provider = provider(baseUrl(), Readiness.READY);
        service.reconcile(provider);

        new JdbcTemplate(dataSource).update(
                "UPDATE agent_registry_mvp SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 hour' "
                        + "WHERE tenant_id = ? AND instance_id = ?",
                TENANT, INSTANCE_ID);

        // Same revision as first reconcile — skips observation re-upsert (which would refresh lease).
        service.reconcile(fixedRevisionProvider(baseUrl(), Readiness.READY, 1L));

        String lifecycle = readLifecycle();
        assertThat(lifecycle).isEqualTo("REMOVED");
    }

    private static ReconciliationService reconciliationService() {
        DeploymentDiscoveryProperties properties = new DeploymentDiscoveryProperties();
        properties.setDrainingGracePeriod(Duration.ofSeconds(30));
        return new ReconciliationService(
                repository,
                new AgentCardFetcher(),
                properties,
                List.of(binding()));
    }

    private static ReconciliationService.StaticInstanceRuntimeBinding binding() {
        return new ReconciliationService.StaticInstanceRuntimeBinding(
                TENANT, SERVICE_ID, INSTANCE_ID,
                com.openjiuwen.rdc.model.FrameworkType.JIUWEN,
                "/v1/query", "1.0.0", "1.0.0",
                "/.well-known/agent-card.json", Map.of(), 10, 100, "cn-east-1");
    }

    private static StaticDeploymentDiscoveryProvider provider(String baseUrl, Readiness readiness) {
        return new StaticDeploymentDiscoveryProvider(List.of(
                new StaticDeploymentDiscoveryProvider.StaticInstanceConfig(
                        TENANT, SERVICE_ID, INSTANCE_ID, baseUrl, "1.0.0", readiness)));
    }

    private static com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider fixedRevisionProvider(
            String baseUrl, Readiness readiness, long revision) {
        return new com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider() {
            @Override
            public String sourceId() {
                return StaticDeploymentDiscoveryProvider.SOURCE_ID;
            }

            @Override
            public com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult listInstances() {
                var obs = new com.openjiuwen.rdc.model.deployment.DeploymentInstanceObservation(
                        TENANT, SERVICE_ID, INSTANCE_ID, baseUrl, "1.0.0", readiness,
                        StaticDeploymentDiscoveryProvider.SOURCE_ID, revision, Instant.now());
                return new com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult(
                        StaticDeploymentDiscoveryProvider.SOURCE_ID, revision, List.of(obs));
            }
        };
    }

    private static String baseUrl() {
        return runtimeServer.url("/").toString().replaceAll("/$", "");
    }

    private static MockResponse validCardResponse() {
        return new MockResponse()
                .setBody("""
                        {
                          "name": "life-agent",
                          "description": "life",
                          "version": "1.0.0",
                          "defaultInputModes": ["text"],
                          "defaultOutputModes": ["text"],
                          "capabilities": {"streaming": true},
                          "skills": [],
                          "supportedInterfaces": [{"protocol": "jsonrpc", "url": "/a2a"}]
                        }
                        """)
                .setHeader("Content-Type", "application/json");
    }

    private String readLifecycle() {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT lifecycle_status FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, INSTANCE_ID);
    }

    private String readEffectiveHealth() {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT effective_health FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, INSTANCE_ID);
    }

    private static com.openjiuwen.rdc.model.DiscoveryResult discover(
            String agentId, HealthRequirement healthRequirement) {
        RegistryRequestContext ctx = new RegistryRequestContext(
                TENANT, "life-test", "trace-1", "req-1", Instant.now().plusSeconds(30));
        return discovery.discover(DiscoveryQuery.builder()
                .context(ctx)
                .agentId(agentId)
                .constraints(DiscoveryConstraints.builder()
                        .healthRequirement(healthRequirement)
                        .build())
                .limit(10)
                .build());
    }
}
