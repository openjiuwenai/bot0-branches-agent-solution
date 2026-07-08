package com.openjiuwen.rdc.registry.runtime.pull;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.spi.registry.AgentRegistryEntry;
import com.openjiuwen.rdc.spi.registry.FrameworkType;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PullRegistrationBootstrap} — serial GET + upsert +
 * single-runtime failure skip (REQ-2026-004).
 *
 * <p>Uses {@link MockWebServer} to serve A2A AgentCard JSON and a recording
 * fake {@link AgentRegistryRepository} to assert upsert calls.
 */
class PullRegistrationBootstrapTest {

    private static MockWebServer server;
    private static RegistryObservabilityConfig observability;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void bootServer() throws Exception {
        server = new MockWebServer();
        server.start();
        observability = new RegistryObservabilityConfig(new SimpleMeterRegistry());
        objectMapper = new ObjectMapper();
    }

    @AfterAll
    static void shutDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @BeforeEach
    void resetServer() {
        server.getRequestCount();
    }

    @Test
    void disabled_properties_no_op_on_ready_event() {
        PullRegistrationProperties props = new PullRegistrationProperties();
        props.setEnabled(false);
        RecordingRepository repo = new RecordingRepository();
        PullRegistrationBootstrap bootstrap = new PullRegistrationBootstrap(
                props, repo, observability, objectMapper);

        bootstrap.runBootstrap();

        assertThat(repo.upserts).isEmpty();
    }

    @Test
    void enabled_with_one_runtime_upserts_card() throws Exception {
        String baseUrl = server.url("/").toString().replaceAll("/$", "");
        String cardJson = "{\"name\":\"财务助手\",\"description\":\"billing\","
                + "\"version\":\"1.0.0\",\"capabilities\":{\"streaming\":false,"
                + "\"pushNotifications\":false,\"stateTransitionHistory\":false},"
                + "\"defaultInputModes\":[],\"defaultOutputModes\":[],"
                + "\"skills\":[],\"supportedInterfaces\":[]}";

        server.enqueue(new MockResponse().setBody(cardJson).setHeader("Content-Type", "application/json"));

        PullRegistrationProperties props = new PullRegistrationProperties();
        props.setEnabled(true);
        PullRegistrationProperties.RuntimeEntry entry = new PullRegistrationProperties.RuntimeEntry();
        entry.setBaseUrl(baseUrl);
        entry.setTenantId("tenant-A");
        entry.setAgentId("agent-001");
        entry.setFrameworkType(FrameworkType.JIUWEN);
        props.setRuntimes(List.of(entry));

        RecordingRepository repo = new RecordingRepository();
        PullRegistrationBootstrap bootstrap = new PullRegistrationBootstrap(
                props, repo, observability, objectMapper);

        bootstrap.runBootstrap();

        assertThat(repo.upserts).hasSize(1);
        AgentRegistryEntry upserted = repo.upserts.get(0).entry();
        assertThat(upserted.getTenantId()).isEqualTo("tenant-A");
        assertThat(upserted.getAgentId()).isEqualTo("agent-001");
        assertThat(upserted.getFrameworkType()).isEqualTo(FrameworkType.JIUWEN);
        assertThat(upserted.getEndpointUrl()).isEqualTo(baseUrl);
        assertThat(upserted.getAgentName()).isEqualTo("财务助手");
    }

    @Test
    void single_runtime_failure_skipped_does_not_block_others() throws Exception {
        String goodBase = server.url("/").toString().replaceAll("/$", "");
        String cardJson = "{\"name\":\"agent-2\",\"description\":\"d\","
                + "\"version\":\"1.0.0\",\"capabilities\":{\"streaming\":false,"
                + "\"pushNotifications\":false,\"stateTransitionHistory\":false},"
                + "\"defaultInputModes\":[],\"defaultOutputModes\":[],"
                + "\"skills\":[],\"supportedInterfaces\":[]}";

        // First runtime: 500 error (failure).
        server.enqueue(new MockResponse().setResponseCode(500));
        // Second runtime: valid card.
        server.enqueue(new MockResponse().setBody(cardJson).setHeader("Content-Type", "application/json"));

        PullRegistrationProperties props = new PullRegistrationProperties();
        props.setEnabled(true);

        PullRegistrationProperties.RuntimeEntry bad = new PullRegistrationProperties.RuntimeEntry();
        bad.setBaseUrl(goodBase);
        bad.setTenantId("tenant-bad");
        bad.setAgentId("agent-bad");
        bad.setFrameworkType(FrameworkType.JIUWEN);

        PullRegistrationProperties.RuntimeEntry good = new PullRegistrationProperties.RuntimeEntry();
        good.setBaseUrl(goodBase);
        good.setTenantId("tenant-good");
        good.setAgentId("agent-good");
        good.setFrameworkType(FrameworkType.AGENTSCOPE);

        props.setRuntimes(List.of(bad, good));

        RecordingRepository repo = new RecordingRepository();
        PullRegistrationBootstrap bootstrap = new PullRegistrationBootstrap(
                props, repo, observability, objectMapper);

        bootstrap.runBootstrap();

        // Only the good runtime produced an upsert; the bad one was skipped.
        assertThat(repo.upserts).hasSize(1);
        assertThat(repo.upserts.get(0).entry().getAgentId()).isEqualTo("agent-good");
    }

    private static final class RecordingRepository implements AgentRegistryRepository {
        final List<UpsertCall> upserts = new ArrayList<>();

        record UpsertCall(AgentRegistryEntry entry, String a2aAgentCardJson) {
        }

        @Override
        public void upsert(AgentRegistryEntry entry, String a2aAgentCardJson) {
            upserts.add(new UpsertCall(entry, a2aAgentCardJson));
        }

        @Override
        public boolean delete(String tenantId, String agentId) {
            return false;
        }

        @Override
        public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            return List.of();
        }

        @Override
        public boolean updateStatus(String tenantId, String agentId,
                                    String newStatus, boolean refreshHeartbeat) {
            return false;
        }

        @Override
        public Optional<RegistryRow> searchByAgentId(String tenantId, String agentId) {
            return Optional.empty();
        }

        @Override
        public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId) {
            return Optional.empty();
        }
    }
}
