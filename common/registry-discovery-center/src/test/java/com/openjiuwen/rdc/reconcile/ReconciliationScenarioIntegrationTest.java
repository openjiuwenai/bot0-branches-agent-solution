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
import com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceObservation;
import com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.model.DiscoveryConstraints;
import com.openjiuwen.rdc.model.DiscoveryOutcome;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.DiscoveryResult;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.Freshness;
import com.openjiuwen.rdc.model.HealthRequirement;
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

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

/**
 * * Feat-015 §4 / §6 scenario coverage: rolling upgrade, provider recovery, instance recovery.
  *
 * @since 0.1.0 (2026)
*/
class ReconciliationScenarioIntegrationTest {
    private static final class TestProbeException extends RuntimeException {
        TestProbeException(String message) {
            super(message);
        }
    }

    private static DataSource dataSource;
    private static MockWebServer oldRuntimeServer;
    private static MockWebServer newRuntimeServer;
    private static JdbcAgentRegistryRepository repository;
    private static PgMvpDiscoveryServiceImpl discovery;

    private static final String TENANT = "tenant-scenario";
    private static final String SERVICE_ID = "roll-svc";
    private static final String INSTANCE_OLD = "roll-svc-pod-0";
    private static final String INSTANCE_NEW = "roll-svc-pod-1";

    @BeforeAll
    static void bootStack() throws Exception {
        dataSource = EmbeddedPostgresTestSupport.sharedDataSource();

        oldRuntimeServer = new MockWebServer();
        newRuntimeServer = new MockWebServer();
        oldRuntimeServer.start();
        newRuntimeServer.start();

        repository = new JdbcAgentRegistryRepository(dataSource);
        discovery = new PgMvpDiscoveryServiceImpl(
                repository,
                new com.openjiuwen.rdc.tenant.ThreadLocalTenantContext(),
                new RegistryObservabilityConfig(new SimpleMeterRegistry()),
                null);
    }

    @AfterAll
    static void shutDown() throws Exception {
        if (oldRuntimeServer != null) {
            oldRuntimeServer.shutdown();
        }
        if (newRuntimeServer != null) {
            newRuntimeServer.shutdown();
        }
    }

    @BeforeEach
    void clean() throws Exception {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("DELETE FROM agent_card_source_ref");
            jdbc.execute("DELETE FROM agent_card_registration");
            jdbc.execute("DELETE FROM agent_registry_mvp");
            jdbc.execute("DELETE FROM registry_source_state");
            restartServers();
        }

    private static void restartServers() throws Exception {
        for (MockWebServer server : List.of(oldRuntimeServer, newRuntimeServer)) {
            if (server != null) {
                try {
                    server.shutdown();
                } catch (IOException ignored) {
                    // Best-effort isolation between tests.
                }
            }
        }
        oldRuntimeServer = new MockWebServer();
        newRuntimeServer = new MockWebServer();
        oldRuntimeServer.start();
        newRuntimeServer.start();
    }

    @Test
    void rolling_upgrade_drains_old_filters_version() throws Exception {
        oldRuntimeServer.enqueue(cardResponse("roll-agent-v1", "1.0.0"));
        newRuntimeServer.enqueue(cardResponse("roll-agent-v2", "2.0.0"));

        ReconciliationService service = dualInstanceService();
        StaticDeploymentDiscoveryProvider bothReady = dualProvider(Readiness.READY, Readiness.READY);
        ReconciliationService.ReconciliationResult first = service.reconcile(bothReady);
        assertThat(first.success()).isTrue();
        assertThat(first.created()).isGreaterThanOrEqualTo(1);

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);

        DiscoveryResult both = discover(agentId, null, HealthRequirement.HEALTHY);
        assertThat(both.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(both.candidates()).hasSize(2);
        assertThat(both.candidates().stream().map(c -> c.capabilityVersion()).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("1.0.0", "2.0.0");

        DiscoveryResult v2Only = discover(agentId, "2.0.0", HealthRequirement.HEALTHY);
        assertThat(v2Only.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(v2Only.candidates()).hasSize(1);
        assertThat(v2Only.candidates().get(0).capabilityVersion()).isEqualTo("2.0.0");

        // Rolling upgrade: old pod leaves snapshot, new pod stays ready (revision 3 > 2).
        newRuntimeServer.enqueue(cardResponse("roll-agent-v2", "2.0.0"));
        ReconciliationService.ReconciliationResult upgrade = service.reconcile(
                snapshotProvider(List.of(instanceConfig(newRuntimeServer, INSTANCE_NEW, Readiness.READY)), 3L));
        assertThat(upgrade.success()).isTrue();
        assertThat(upgrade.draining()).isGreaterThanOrEqualTo(1);

        assertThat(readLifecycle(INSTANCE_OLD)).isEqualTo("DRAINING");
        assertThat(readLifecycle(INSTANCE_NEW)).isEqualTo("ACTIVE");

        DiscoveryResult afterUpgrade = discover(agentId, null, HealthRequirement.HEALTHY);
        assertThat(afterUpgrade.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(afterUpgrade.candidates()).hasSize(1);
        assertThat(afterUpgrade.candidates().get(0).capabilityVersion()).isEqualTo("2.0.0");

        assertThat(discover(agentId, "1.0.0", HealthRequirement.HEALTHY).outcome())
                .isEqualTo(DiscoveryOutcome.VERSION_UNAVAILABLE);
    }

    @Test
    void provider_outage_marks_stale_then_recovers() throws Exception {
        oldRuntimeServer.enqueue(cardResponse("solo-agent", "1.0.0"));

        ReconciliationService service = singleInstanceService();
        StaticDeploymentDiscoveryProvider provider = singleProvider(Readiness.READY);
        assertThat(service.reconcile(provider).success()).isTrue();

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        assertThat(discover(agentId, null, HealthRequirement.HEALTHY).outcome())
                .isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(readFreshness(INSTANCE_OLD)).isEqualTo("FRESH");

        ReconciliationService.ReconciliationResult outage =
                service.reconcile(unavailableProvider());
        assertThat(outage.success()).isFalse();
        assertThat(outage.failureCode()).isEqualTo("DEPLOYMENT_SOURCE_UNAVAILABLE");

        assertThat(readLifecycle(INSTANCE_OLD)).isEqualTo("ACTIVE");
        assertThat(readFreshness(INSTANCE_OLD)).isEqualTo("STALE_SOURCE");

        DiscoveryResult duringOutage = discover(agentId, null, HealthRequirement.HEALTHY);
        assertThat(duringOutage.outcome()).isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(duringOutage.candidates()).hasSize(1);
        assertThat(duringOutage.candidates().get(0).freshness()).isEqualTo(Freshness.STALE_SOURCE);

        oldRuntimeServer.enqueue(cardResponse("solo-agent", "1.0.0"));
        ReconciliationService.ReconciliationResult recovery = service.reconcile(
                snapshotProvider(List.of(instanceConfig(oldRuntimeServer, INSTANCE_OLD, Readiness.READY)), 3L));
        assertThat(recovery.success()).isTrue();
        assertThat(readFreshness(INSTANCE_OLD)).isEqualTo("FRESH");
        assertThat(discover(agentId, null, HealthRequirement.HEALTHY).candidates().get(0).freshness())
                .isEqualTo(Freshness.FRESH);
    }

    @Test
    void degraded_instance_recovers_after_successful_card_refresh() throws Exception {
        oldRuntimeServer.enqueue(cardResponse("recover-agent", "1.0.0"));

        ReconciliationService service = singleInstanceService();
        StaticDeploymentDiscoveryProvider provider = singleProvider(Readiness.READY);
        service.reconcile(provider);
        assertThat(readEffectiveHealth(INSTANCE_OLD)).isEqualTo("HEALTHY");

        oldRuntimeServer.enqueue(new MockResponse().setResponseCode(503));
        assertThat(service.reconcile(provider).success()).isTrue();
        assertThat(readEffectiveHealth(INSTANCE_OLD)).isEqualTo("DEGRADED");

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        assertThat(discover(agentId, null, HealthRequirement.HEALTHY).outcome())
                .isEqualTo(DiscoveryOutcome.SUCCESS);
        assertThat(discover(agentId, null, HealthRequirement.HEALTHY_OR_DEGRADED).outcome())
                .isEqualTo(DiscoveryOutcome.SUCCESS);

        oldRuntimeServer.enqueue(cardResponse("recover-agent", "1.0.0"));
        assertThat(service.reconcile(provider).success()).isTrue();
        assertThat(readEffectiveHealth(INSTANCE_OLD)).isEqualTo("HEALTHY");
        assertThat(discover(agentId, null, HealthRequirement.HEALTHY).outcome())
                .isEqualTo(DiscoveryOutcome.SUCCESS);
    }

    private static ReconciliationService singleInstanceService() {
        DeploymentDiscoveryProperties properties = new DeploymentDiscoveryProperties();
        properties.setDrainingGracePeriod(Duration.ofSeconds(30));
        return new ReconciliationService(
                repository,
                new AgentCardFetcher(),
                properties,
                List.of(binding(INSTANCE_OLD, "1.0.0")));
    }

    private static ReconciliationService dualInstanceService() {
        DeploymentDiscoveryProperties properties = new DeploymentDiscoveryProperties();
        properties.setDrainingGracePeriod(Duration.ofSeconds(30));
        return new ReconciliationService(
                repository,
                new AgentCardFetcher(),
                properties,
                List.of(
                        binding(INSTANCE_OLD, "1.0.0"),
                        binding(INSTANCE_NEW, "2.0.0")));
    }

    private static ReconciliationService.StaticInstanceRuntimeBinding binding(
            String instanceId, String capabilityVersion) {
        return new ReconciliationService.StaticInstanceRuntimeBinding(
                TENANT, SERVICE_ID, instanceId,
                FrameworkType.JIUWEN,
                "/v1/query", "1.0.0", capabilityVersion,
                "/.well-known/agent-card.json", Map.of(), 10, 100, "cn-east-1");
    }

    private static StaticDeploymentDiscoveryProvider singleProvider(Readiness readiness) {
        return new StaticDeploymentDiscoveryProvider(List.of(
                instanceConfig(oldRuntimeServer, INSTANCE_OLD, readiness)));
    }

    private static StaticDeploymentDiscoveryProvider dualProvider(
            Readiness oldReadiness, Readiness newReadiness) {
        return new StaticDeploymentDiscoveryProvider(List.of(
                instanceConfig(oldRuntimeServer, INSTANCE_OLD, oldReadiness),
                instanceConfig(newRuntimeServer, INSTANCE_NEW, newReadiness)));
    }

    private static StaticDeploymentDiscoveryProvider.StaticInstanceConfig instanceConfig(
            MockWebServer server, String instanceId, Readiness readiness) {
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        return new StaticDeploymentDiscoveryProvider.StaticInstanceConfig(
                TENANT, SERVICE_ID, instanceId, baseUrl, "1.0.0", readiness);
    }

    private static DeploymentDiscoveryProvider snapshotProvider(
            List<StaticDeploymentDiscoveryProvider.StaticInstanceConfig> configs, long revision) {
        return new DeploymentDiscoveryProvider() {
            @Override
            public String sourceId() {
        return StaticDeploymentDiscoveryProvider.SOURCE_ID;
    }
            @Override
            public ListDeploymentInstancesResult listInstances() {
                Instant now = Instant.now();
                List<DeploymentInstanceObservation> observations = configs.stream()
                        .map(cfg -> new DeploymentInstanceObservation(
                                cfg.tenantId(),
                                cfg.serviceId(),
                                cfg.instanceId(),
                                cfg.baseUrl(),
                                cfg.deploymentVersion(),
                                cfg.readiness(),
                                StaticDeploymentDiscoveryProvider.SOURCE_ID,
                                revision,
                                now))
                        .toList();
                return new ListDeploymentInstancesResult(
                        StaticDeploymentDiscoveryProvider.SOURCE_ID, revision, observations);
            }
        };
    }

    private static DeploymentDiscoveryProvider unavailableProvider() {
        return new DeploymentDiscoveryProvider() {
            @Override
            public String sourceId() {
        return StaticDeploymentDiscoveryProvider.SOURCE_ID;
    }
            @Override
            public ListDeploymentInstancesResult listInstances() {
                throw new TestProbeException("provider unreachable");
            }
        };
    }

    private static MockResponse cardResponse(String name, String version) {
        return new MockResponse()
                .setBody("""
                        {
                          "name": "%s",
                          "description": "scenario",
                          "version": "%s",
                          "defaultInputModes": ["text"],
                          "defaultOutputModes": ["text"],
                          "capabilities": {"streaming": true},
                          "skills": [],
                          "supportedInterfaces": [{"protocol": "jsonrpc", "url": "/a2a"}]
                        }
                        """.formatted(name, version))
                .setHeader("Content-Type", "application/json");
    }

    private DiscoveryResult discover(String agentId, String capabilityVersion,
                                     HealthRequirement healthRequirement) {
        RegistryRequestContext ctx = new RegistryRequestContext(
                TENANT, "scenario-test", "trace-1", "req-1", Instant.now().plusSeconds(30));
        DiscoveryQuery.Builder builder = DiscoveryQuery.builder()
                .context(ctx)
                .agentId(agentId)
                .limit(10)
                .constraints(DiscoveryConstraints.builder()
                        .healthRequirement(healthRequirement)
                        .build());
        if (capabilityVersion != null) {
            builder.constraints(DiscoveryConstraints.builder()
                    .capabilityVersion(capabilityVersion)
                    .healthRequirement(healthRequirement)
                    .build());
        }
        return discovery.discover(builder.build());
    }

    private String readLifecycle(String instanceId) {
            return new JdbcTemplate(dataSource).queryForObject(
            "SELECT lifecycle_status FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
            String.class, TENANT, instanceId);
        }

    private String readEffectiveHealth(String instanceId) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT effective_health FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, instanceId);
    }

    private String readFreshness(String instanceId) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT freshness FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, instanceId);
    }
}
