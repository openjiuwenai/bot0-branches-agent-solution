/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.AgentIdCodec;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.DiscoveryResult;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.RegistryRequestContext;
import com.openjiuwen.rdc.repository.EmbeddedPostgresTestSupport;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.service.PgMvpDiscoveryServiceImpl;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Integration tests for Feat-015 P1 provider reconciliation against real PG.
 */
class ReconciliationIntegrationTest {
    private static DataSource dataSource;
    private static MockWebServer runtimeServer;
    private static JdbcAgentRegistryRepository repository;
    private static PgMvpDiscoveryServiceImpl discovery;

    private static final String TENANT = "tenant-rec";
    private static final String SERVICE_ID = "billing-svc";
    private static final String INSTANCE_ID = "billing-svc-pod-0";

    @BeforeAll
    static void bootStack() throws Exception {
        dataSource = EmbeddedPostgresTestSupport.sharedDataSource();

        runtimeServer = new MockWebServer();
        runtimeServer.start();

        repository = new JdbcAgentRegistryRepository(dataSource);
        RegistryObservabilityConfig observability = new RegistryObservabilityConfig(new SimpleMeterRegistry());
        discovery = new PgMvpDiscoveryServiceImpl(
                repository,
                new com.openjiuwen.rdc.tenant.ThreadLocalTenantContext(),
                observability,
                null);
    }

    @AfterAll
    static void shutDown() throws Exception {
        if (runtimeServer != null) {
            runtimeServer.shutdown();
        }
    }

    @BeforeEach
    void cleanTable() {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("DELETE FROM agent_card_source_ref");
            jdbc.execute("DELETE FROM agent_card_registration");
            jdbc.execute("DELETE FROM agent_registry_mvp");
            jdbc.execute("DELETE FROM registry_source_state");
        }

    @Test
    void reconcile_persists_and_discovery_finds() throws Exception {
        enqueueValidCard();
        ReconciliationService service = reconciliationService();

        ReconciliationService.ReconciliationResult result =
                service.reconcile(provider(baseUrl(), Readiness.READY));

        assertThat(result.success()).isTrue();
        assertThat(result.created() + result.updated()).isGreaterThanOrEqualTo(1);

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        DiscoveryResult discoveryResult = discover(agentId);
        assertThat(discoveryResult.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(discoveryResult.candidates()).hasSize(1);
        assertThat(discoveryResult.candidates().get(0).agentId()).isEqualTo(agentId);

        Integer logicalRows = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM agent_card_registration WHERE tenant_id = ? AND agent_id = ? "
                        + "AND registration_status = 'REGISTERED'",
                Integer.class, TENANT, agentId);
        assertThat(logicalRows).isEqualTo(1);

        String persistedAgentId = new JdbcTemplate(dataSource).queryForObject(
                "SELECT agent_id FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, INSTANCE_ID);
        assertThat(persistedAgentId).isEqualTo(agentId);
    }

    @Test
    void reconcile_without_yml_binding_uses_binding_defaults() throws Exception {
        enqueueValidCard();
        DeploymentDiscoveryProperties properties = new DeploymentDiscoveryProperties();
        properties.setDrainingGracePeriod(Duration.ofSeconds(30));
        // No per-instance bindings — dynamic providers must rely on binding-defaults.
        ReconciliationService service = new ReconciliationService(
                repository, new AgentCardFetcher(), properties, List.of());

        ReconciliationService.ReconciliationResult result =
                service.reconcile(provider(baseUrl(), Readiness.READY));

        assertThat(result.success()).isTrue();
        assertThat(result.created() + result.updated()).isGreaterThanOrEqualTo(1);

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        DiscoveryResult discoveryResult = discover(agentId);
        assertThat(discoveryResult.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(discoveryResult.candidates()).hasSize(1);
    }

    @Test
    void reconcile_missing_instance_marks_draining() throws Exception {
        enqueueValidCard();
        ReconciliationService service = reconciliationService();
        StaticDeploymentDiscoveryProvider provider = provider(baseUrl(), Readiness.READY);
        service.reconcile(provider);

        ReconciliationService.ReconciliationResult secondPass =
                service.reconcile(emptySnapshotProvider(3L));

        assertThat(secondPass.success()).isTrue();
        assertThat(secondPass.draining()).isGreaterThanOrEqualTo(1);

        String lifecycle = new JdbcTemplate(dataSource).queryForObject(
                "SELECT lifecycle_status FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, INSTANCE_ID);
        assertThat(lifecycle).isEqualTo("DRAINING");
    }

    private static ReconciliationService reconciliationService() {
        DeploymentDiscoveryProperties properties = new DeploymentDiscoveryProperties();
        properties.setDrainingGracePeriod(Duration.ofSeconds(30));
        ReconciliationService.StaticInstanceRuntimeBinding binding =
                new ReconciliationService.StaticInstanceRuntimeBinding(
                        TENANT,
                        SERVICE_ID,
                        INSTANCE_ID,
                        FrameworkType.JIUWEN,
                        "/v1/query",
                        "1.0.0",
                        "1.0.0",
                        "/.well-known/agent-card.json",
                        Map.of(),
                        10,
                        100,
                        "cn-east-1");
        return new ReconciliationService(
                repository, new AgentCardFetcher(), properties, List.of(binding));
    }

    private static StaticDeploymentDiscoveryProvider provider(String baseUrl, Readiness readiness) {
        return new StaticDeploymentDiscoveryProvider(List.of(
                new StaticDeploymentDiscoveryProvider.StaticInstanceConfig(
                        TENANT, SERVICE_ID, INSTANCE_ID, baseUrl, "1.0.0", readiness)));
    }

    private static com.openjiuwen.rdc.model.deployment
            .DeploymentDiscoveryProvider emptySnapshotProvider(long revision) {
        return new com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider() {
            @Override
            public String sourceId() {
                return StaticDeploymentDiscoveryProvider.SOURCE_ID;
            }
            @Override
            public com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult listInstances() {
                return new com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult(
                        StaticDeploymentDiscoveryProvider.SOURCE_ID, revision, List.of());
            }
        };
    }

    private static String baseUrl() {
        return runtimeServer.url("/").toString().replaceAll("/$", "");
    }
    private static void enqueueValidCard() {
        runtimeServer.enqueue(new MockResponse()
                .setBody(validCardJson())
                .setHeader("Content-Type", "application/json"));
    }

    private static String validCardJson() {
        return "{"
                + "\"name\":\"billing-agent\","
                + "\"description\":\"billing\","
                + "\"version\":\"1.0.0\","
                + "\"defaultInputModes\":[\"text\"],"
                + "\"defaultOutputModes\":[\"text\"],"
                + "\"capabilities\":{\"streaming\":true},"
                + "\"skills\":[{\"id\":\"pay-skill\",\"name\":\"pay\",\"description\":\"d\",\"tags\":[\"billing\"]}],"
                + "\"capabilities\":{\"streaming\":true},"
                + "\"supportedInterfaces\":[{\"protocol\":\"jsonrpc\",\"url\":\"/a2a\"}]"
                + "}";
    }

    private static DiscoveryResult discover(String agentId) {
        RegistryRequestContext ctx = new RegistryRequestContext(
                TENANT, "reconcile-test", "trace-1", "req-1", Instant.now().plusSeconds(30));
        DiscoveryQuery query = DiscoveryQuery.builder()
                .context(ctx)
                .agentId(agentId)
                .limit(10)
                .build();
        return discovery.discover(query);
    }
}
