package com.openjiuwen.rdc.reconcile;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.repository.EmbeddedPostgresTestSupport;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.model.FrameworkType;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mirrors {@link ReconciliationScheduler}: provider registers watch before reconcile.
 * Ensures missing instances are drained even when watch events bump revision first.
 */
class ReconciliationWatchIntegrationTest {

    private static DataSource dataSource;
    private static MockWebServer oldRuntimeServer;
    private static MockWebServer newRuntimeServer;
    private static JdbcAgentRegistryRepository repository;

    private static final String TENANT = "tenant-watch";
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

    private static void restartServers() throws Exception {
        for (MockWebServer server : List.of(oldRuntimeServer, newRuntimeServer)) {
            if (server != null) {
                try {
                    server.shutdown();
                } catch (Exception ignored) {
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
    void stale_revision_still_drains_missing_instance_with_watch() throws Exception {
        oldRuntimeServer.enqueue(cardResponse("billing-agent-v1", "1.0.0"));
        newRuntimeServer.enqueue(cardResponse("billing-agent-v2", "2.0.0"));

        ReconciliationService service = dualInstanceService();
        StaticDeploymentDiscoveryProvider bothPods = dualProvider();
        bothPods.watchInstances(service::reconcileEvent);
        assertThat(service.reconcile(bothPods).success()).isTrue();

        new JdbcTemplate(dataSource).update(
                "UPDATE registry_source_state SET last_processed_revision = 99 WHERE source_id = ?",
                StaticDeploymentDiscoveryProvider.SOURCE_ID);

        newRuntimeServer.enqueue(cardResponse("billing-agent-v2", "2.0.0"));
        StaticDeploymentDiscoveryProvider newPodOnly = new StaticDeploymentDiscoveryProvider(List.of(
                instanceConfig(newRuntimeServer, INSTANCE_NEW, Readiness.READY)));
        newPodOnly.watchInstances(service::reconcileEvent);

        ReconciliationService.ReconciliationResult stale =
                service.reconcile(newPodOnly);
        assertThat(stale.success()).isTrue();
        assertThat(stale.draining()).isGreaterThanOrEqualTo(1);
        assertThat(readLifecycle(INSTANCE_OLD)).isEqualTo("DRAINING");
        assertThat(readLifecycle(INSTANCE_NEW)).isEqualTo("ACTIVE");
    }

    @Test
    void watch_registered_reconcile_drains_instance_missing_from_snapshot() throws Exception {
        oldRuntimeServer.enqueue(cardResponse("billing-agent-v1", "1.0.0"));
        newRuntimeServer.enqueue(cardResponse("billing-agent-v2", "2.0.0"));

        ReconciliationService service = dualInstanceService();
        StaticDeploymentDiscoveryProvider bothPods = dualProvider();
        bothPods.watchInstances(service::reconcileEvent);

        assertThat(service.reconcile(bothPods).success()).isTrue();
        assertThat(readLifecycle(INSTANCE_OLD)).isEqualTo("ACTIVE");
        assertThat(readLifecycle(INSTANCE_NEW)).isEqualTo("ACTIVE");

        newRuntimeServer.enqueue(cardResponse("billing-agent-v2", "2.0.0"));
        StaticDeploymentDiscoveryProvider newPodOnly = new StaticDeploymentDiscoveryProvider(List.of(
                instanceConfig(newRuntimeServer, INSTANCE_NEW, Readiness.READY)));
        newPodOnly.watchInstances(service::reconcileEvent);

        ReconciliationService.ReconciliationResult rolling =
                service.reconcile(newPodOnly);
        assertThat(rolling.success()).isTrue();
        assertThat(rolling.draining()).isGreaterThanOrEqualTo(1);
        assertThat(readLifecycle(INSTANCE_OLD)).isEqualTo("DRAINING");
        assertThat(readLifecycle(INSTANCE_NEW)).isEqualTo("ACTIVE");
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
                          "description": "watch test",
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
