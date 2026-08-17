/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.AgentIdCodec;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.model.deployment.Readiness;
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
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * Rolling-upgrade acceptance for FEAT-016 runtime routing (Feat-Func-016 L2 §4.4).
 *
 * <p>Chains reconcile (old pod → {@code DRAINING}) with
 * {@link com.openjiuwen.rdc.service.AgentDiscoveryService#searchInstancesByAgentId}.
 * Callers that pick-first from this list (gateway {@code HttpRdcRouteClient},
 * runtime {@code AgentBusRemoteAgentCaller}) cannot enqueue new
 * {@code A2A_CALL_REQUESTED} traffic toward the drained instance because its
 * {@code routeHandle} never appears in discovery results.
 */
class RollingUpgradeRuntimeDiscoveryIntegrationTest {
    private static DataSource dataSource;
    private static MockWebServer oldRuntimeServer;
    private static MockWebServer newRuntimeServer;
    private static JdbcAgentRegistryRepository repository;
    private static PgMvpDiscoveryServiceImpl discovery;

    private static final String TENANT = "tenant-rolling";
    private static final String SERVICE_ID = "billing-svc";
    private static final String INSTANCE_OLD = "billing-svc-pod-0";
    private static final String INSTANCE_NEW = "billing-svc-pod-1";

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
        jdbc.execute("DELETE FROM agent_registry_mvp");
        jdbc.execute("DELETE FROM registry_source_state");
        restartServers();
    }

    @Test
    void after_rolling_reconcile_runtime_discovery_returns_only_surviving_instance() throws Exception {
        oldRuntimeServer.enqueue(cardResponse("billing-agent-v1", "1.0.0"));
        newRuntimeServer.enqueue(cardResponse("billing-agent-v2", "2.0.0"));

        ReconciliationService service = dualInstanceService();
        StaticDeploymentDiscoveryProvider bothPods = dualProvider();
        bothPods.watchInstances(service::reconcileEvent);
        assertThat(service.reconcile(bothPods).success()).isTrue();
        assertThat(readLifecycle(INSTANCE_OLD)).isEqualTo("ACTIVE");
        assertThat(readLifecycle(INSTANCE_NEW)).isEqualTo("ACTIVE");

        String agentId = AgentIdCodec.derive(TENANT, SERVICE_ID);
        String oldRouteHandle = discovery.searchInstancesByAgentId(TENANT, agentId).stream()
                .filter(dto -> INSTANCE_OLD.equals(
                        discovery.resolveRouteHandle(dto.getRouteHandle(), TENANT).instanceId()))
                .map(AgentCardDto::getRouteHandle)
                .findFirst()
                .orElseThrow();

        newRuntimeServer.enqueue(cardResponse("billing-agent-v2", "2.0.0"));
        StaticDeploymentDiscoveryProvider newPodOnly = new StaticDeploymentDiscoveryProvider(List.of(
                instanceConfig(newRuntimeServer, INSTANCE_NEW, Readiness.READY)));
        newPodOnly.watchInstances(service::reconcileEvent);

        ReconciliationService.ReconciliationResult rolling = service.reconcile(newPodOnly);
        assertThat(rolling.success()).isTrue();
        assertThat(rolling.draining()).isGreaterThanOrEqualTo(1);
        assertThat(readLifecycle(INSTANCE_OLD)).isEqualTo("DRAINING");
        assertThat(readLifecycle(INSTANCE_NEW)).isEqualTo("ACTIVE");

        List<AgentCardDto> candidates = discovery.searchInstancesByAgentId(TENANT, agentId);
        assertThat(candidates).hasSize(1);

        RouteResolution resolution = discovery.resolveRouteHandle(
                candidates.get(0).getRouteHandle(), TENANT);
        assertThat(resolution.instanceId()).isEqualTo(INSTANCE_NEW);
        assertThat(candidates.stream().map(AgentCardDto::getRouteHandle))
                .doesNotContain(oldRouteHandle);
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

    private static StaticDeploymentDiscoveryProvider dualProvider() {
        return new StaticDeploymentDiscoveryProvider(List.of(
                instanceConfig(oldRuntimeServer, INSTANCE_OLD, Readiness.READY),
                instanceConfig(newRuntimeServer, INSTANCE_NEW, Readiness.READY)));
    }

    private static StaticDeploymentDiscoveryProvider.StaticInstanceConfig instanceConfig(
            MockWebServer server, String instanceId, Readiness readiness) {
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        return new StaticDeploymentDiscoveryProvider.StaticInstanceConfig(
                TENANT, SERVICE_ID, instanceId, baseUrl, "1.0.0", readiness);
    }

    private static MockResponse cardResponse(String name, String version) {
        return new MockResponse()
                .setBody("""
                        {
                          "name": "%s",
                          "description": "rolling upgrade",
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

    private String readLifecycle(String instanceId) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT lifecycle_status FROM agent_registry_mvp WHERE tenant_id = ? AND instance_id = ?",
                String.class, TENANT, instanceId);
    }
}
