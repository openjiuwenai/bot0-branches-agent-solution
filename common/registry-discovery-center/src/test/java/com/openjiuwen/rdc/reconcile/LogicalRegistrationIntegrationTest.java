/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.AgentCardDiscoveryQuery;
import com.openjiuwen.rdc.model.AgentIdCodec;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.Freshness;
import com.openjiuwen.rdc.model.RegistrationStatus;
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
 * Integration tests for logical AgentCardRegistration catalog: multi-instance dedup,
 * last-source removal, and STALE_CARD freshness.
 */
class LogicalRegistrationIntegrationTest {

    private static DataSource dataSource;
    private static MockWebServer runtimeServer;
    private static JdbcAgentRegistryRepository repository;
    private static PgMvpDiscoveryServiceImpl discovery;

    private static final String TENANT = "tenant-logical";
    private static final String SERVICE_ID = "order-svc";
    private static final String INSTANCE_A = "order-svc-pod-a";
    private static final String INSTANCE_B = "order-svc-pod-b";

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
    void cleanTables() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM agent_card_source_ref");
        jdbc.execute("DELETE FROM agent_card_registration");
        jdbc.execute("DELETE FROM agent_registry_mvp");
        jdbc.execute("DELETE FROM registry_source_state");
    }

    @Test
    void multi_instance_same_card_returns_single_logical_candidate() throws Exception {
        enqueueValidCard();
        enqueueValidCard();
        reconciliationService().reconcile(twoInstanceProvider(Readiness.READY, Readiness.READY));

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        var result = discover(agentId);

        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).registrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);

        Integer sourceRefCount = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM agent_card_source_ref WHERE tenant_id = ? AND service_id = ?",
                Integer.class, TENANT, SERVICE_ID);
        assertThat(sourceRefCount).isEqualTo(2);
    }

    @Test
    void last_source_removal_marks_registration_removed_and_hides_from_discovery() throws Exception {
        enqueueValidCard();
        enqueueValidCard();
        ReconciliationService service = reconciliationService();
        service.reconcile(twoInstanceProvider(Readiness.READY, Readiness.READY));

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        assertThat(discover(agentId).outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);

        service.reconcile(emptySnapshotProvider());
        assertThat(discover(agentId).outcome()).isEqualTo(DiscoveryOutcome.NO_MATCH);

        String status = new JdbcTemplate(dataSource).queryForObject(
                "SELECT registration_status FROM agent_card_registration WHERE tenant_id = ? AND service_id = ?",
                String.class, TENANT, SERVICE_ID);
        assertThat(status).isEqualTo("REMOVED");
    }

    @Test
    void card_refresh_failure_marks_stale_card_but_keeps_discoverable() throws Exception {
        enqueueValidCard();
        ReconciliationService service = reconciliationService();
        StaticDeploymentDiscoveryProvider provider = singleInstanceProvider(Readiness.READY);
        service.reconcile(provider);

        runtimeServer.enqueue(new MockResponse().setResponseCode(503));
        service.reconcile(provider);

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        var result = discover(agentId);
        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates().get(0).freshness()).isEqualTo(Freshness.STALE_CARD);
    }

    @Test
    void card_revalidation_after_refresh_failure_restores_fresh_without_card_change() throws Exception {
        enqueueValidCard();
        ReconciliationService service = reconciliationService();
        StaticDeploymentDiscoveryProvider provider = singleInstanceProvider(Readiness.READY);
        service.reconcile(provider);

        runtimeServer.enqueue(new MockResponse().setResponseCode(503));
        service.reconcile(provider);

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        assertThat(discover(agentId).candidates().get(0).freshness()).isEqualTo(Freshness.STALE_CARD);

        enqueueValidCard();
        service.reconcile(provider);

        assertThat(discover(agentId).candidates().get(0).freshness()).isEqualTo(Freshness.FRESH);
        String freshness = new JdbcTemplate(dataSource).queryForObject(
                "SELECT freshness FROM agent_card_registration WHERE tenant_id = ? AND service_id = ?",
                String.class, TENANT, SERVICE_ID);
        assertThat(freshness).isEqualTo("FRESH");
    }

    @Test
    void card_content_change_removes_previous_digest_from_discovery() throws Exception {
        enqueueValidCard();
        ReconciliationService service = reconciliationService();
        StaticDeploymentDiscoveryProvider provider = singleInstanceProvider(Readiness.READY);
        service.reconcile(provider);

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        assertThat(discover(agentId).candidates()).hasSize(1);

        enqueueCardJson(validCardJsonWithDescription("orders-updated"));
        service.reconcile(provider);

        var result = discover(agentId);
        assertThat(result.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(result.candidates()).hasSize(1);
        assertThat(result.candidates().get(0).agentCardJson()).contains("orders-updated");

        Integer registered = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM agent_card_registration WHERE tenant_id = ? AND service_id = ? "
                        + "AND registration_status = 'REGISTERED'",
                Integer.class, TENANT, SERVICE_ID);
        assertThat(registered).isEqualTo(1);

        Integer removed = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM agent_card_registration WHERE tenant_id = ? AND service_id = ? "
                        + "AND registration_status = 'REMOVED'",
                Integer.class, TENANT, SERVICE_ID);
        assertThat(removed).isEqualTo(1);
    }

    @Test
    void unchanged_digest_restores_missing_source_ref() throws Exception {
        enqueueValidCard();
        ReconciliationService service = reconciliationService();
        StaticDeploymentDiscoveryProvider provider = singleInstanceProvider(Readiness.READY);
        service.reconcile(provider);

        new JdbcTemplate(dataSource).update(
                "DELETE FROM agent_card_source_ref WHERE tenant_id = ? AND instance_id = ?",
                TENANT, INSTANCE_A);
        assertThat(new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM agent_card_source_ref WHERE tenant_id = ?",
                Integer.class, TENANT)).isZero();

        enqueueValidCard();
        service.reconcile(provider);

        Integer refs = new JdbcTemplate(dataSource).queryForObject(
                "SELECT COUNT(*) FROM agent_card_source_ref WHERE tenant_id = ? AND instance_id = ?",
                Integer.class, TENANT, INSTANCE_A);
        assertThat(refs).isEqualTo(1);
        assertThat(discover(AgentIdCodec.derive(TENANT, SERVICE_ID)).candidates()).hasSize(1);
    }

    private static com.openjiuwen.rdc.model.AgentCardDiscoveryResult discover(String agentId) {
        RegistryRequestContext ctx = new RegistryRequestContext(
                TENANT, "test", "trace-logical", "req-1", Instant.now().plusSeconds(30));
        return discovery.discoverAgentCards(AgentCardDiscoveryQuery.builder()
                .context(ctx)
                .agentId(agentId)
                .limit(10)
                .build());
    }

    private static ReconciliationService reconciliationService() {
        DeploymentDiscoveryProperties props = new DeploymentDiscoveryProperties();
        props.setDrainingGracePeriod(Duration.ZERO);
        List<ReconciliationService.StaticInstanceRuntimeBinding> bindings = List.of(
                binding(INSTANCE_A),
                binding(INSTANCE_B));
        return new ReconciliationService(repository, new AgentCardFetcher(), props, bindings);
    }

    private static ReconciliationService.StaticInstanceRuntimeBinding binding(String instanceId) {
        return new ReconciliationService.StaticInstanceRuntimeBinding(
                TENANT, SERVICE_ID, instanceId,
                FrameworkType.JIUWEN, "/v1/query", "1.0.0", "1.0.0",
                "/.well-known/agent-card.json", Map.of(), 10, 100, "cn-east-1");
    }

    private static StaticDeploymentDiscoveryProvider twoInstanceProvider(Readiness a, Readiness b) {
        return new StaticDeploymentDiscoveryProvider(List.of(
                config(INSTANCE_A, a),
                config(INSTANCE_B, b)));
    }

    private static StaticDeploymentDiscoveryProvider singleInstanceProvider(Readiness readiness) {
        return new StaticDeploymentDiscoveryProvider(List.of(config(INSTANCE_A, readiness)));
    }

    private static StaticDeploymentDiscoveryProvider.StaticInstanceConfig config(
            String instanceId, Readiness readiness) {
        return new StaticDeploymentDiscoveryProvider.StaticInstanceConfig(
                TENANT, SERVICE_ID, instanceId, baseUrl(), "1.0.0", readiness);
    }

    private static com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider emptySnapshotProvider() {
        return new com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider() {
            @Override
            public String sourceId() {
                return StaticDeploymentDiscoveryProvider.SOURCE_ID;
            }

            @Override
            public com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult listInstances() {
                return new com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult(
                        StaticDeploymentDiscoveryProvider.SOURCE_ID, 99L, List.of());
            }
        };
    }

    private static void enqueueValidCard() {
        enqueueCardJson(validCardJson());
    }

    private static void enqueueCardJson(String body) {
        runtimeServer.enqueue(new MockResponse()
                .setBody(body)
                .setHeader("Content-Type", "application/json"));
    }

    private static String validCardJson() {
        return validCardJsonWithDescription("orders");
    }

    private static String validCardJsonWithDescription(String description) {
        return "{"
                + "\"name\":\"order-agent\","
                + "\"description\":\"" + description + "\","
                + "\"version\":\"1.0.0\","
                + "\"defaultInputModes\":[\"text\"],"
                + "\"defaultOutputModes\":[\"text\"],"
                + "\"capabilities\":{\"streaming\":true},"
                + "\"skills\":[{\"id\":\"place-order\",\"name\":\"place\",\"description\":\"d\",\"tags\":[\"commerce\"]}],"
                + "\"supportedInterfaces\":[{\"protocol\":\"jsonrpc\",\"url\":\"/a2a\",\"version\":\"1.0.0\"}]"
                + "}";
    }

    private static String baseUrl() {
        return runtimeServer.url("/").toString().replaceAll("/$", "");
    }
}
