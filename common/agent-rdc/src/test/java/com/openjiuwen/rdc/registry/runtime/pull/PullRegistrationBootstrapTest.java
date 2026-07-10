/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.rdc.registry.runtime.pull;

import static org.assertj.core.api.Assertions.assertThat;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Unit tests for {@link PullRegistrationBootstrap} — serial GET + upsert +
 * single-runtime failure skip (REQ-2026-004).
 *
 * <p>Uses {@link MockWebServer} to serve A2A AgentCard JSON and a recording
 * fake {@link AgentRegistryRepository} to assert upsert calls.
 *
 * @since 2026-07-10
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

    // --- FEAT-016 Task 8: buildEntry serviceId / instanceId / capabilities ---

    @Test
    void build_entry_uses_explicit_service_id_when_provided() {
        PullRegistrationProperties.RuntimeEntry runtime = baseRuntime("http://10.0.0.1:8080");
        runtime.setServiceId("wealth-svc");
        AgentRegistryEntry entry = invokeBuildEntry(runtime, "wealth-agent");
        assertThat(entry.getServiceId()).isEqualTo("wealth-svc");
        assertThat(entry.getInstanceId()).isEqualTo("10.0.0.1-8080");
    }

    @Test
    void build_entry_derives_service_id_from_base_url_host_when_absent() {
        PullRegistrationProperties.RuntimeEntry runtime = baseRuntime("http://10.0.0.2:9000");
        AgentRegistryEntry entry = invokeBuildEntry(runtime, "wealth-agent");
        assertThat(entry.getServiceId()).isEqualTo("10.0.0.2");
        assertThat(entry.getInstanceId()).isEqualTo("10.0.0.2-9000");
    }

    @Test
    void build_entry_passes_capabilities_through() {
        PullRegistrationProperties.RuntimeEntry runtime = baseRuntime("http://10.0.0.3:8080");
        runtime.setCapabilities(java.util.List.of("wealth.purchase"));
        AgentRegistryEntry entry = invokeBuildEntry(runtime, "wealth-agent");
        assertThat(entry.getCapabilities()).containsExactly("wealth.purchase");
    }

    @Test
    void build_entry_defaults_capabilities_to_empty_when_absent() {
        PullRegistrationProperties.RuntimeEntry runtime = baseRuntime("http://10.0.0.4:8080");
        AgentRegistryEntry entry = invokeBuildEntry(runtime, "wealth-agent");
        assertThat(entry.getCapabilities()).isEmpty();
    }

    /**
     * Minimum-viable RuntimeEntry for buildEntry tests — baseUrl + tenantId +
     * agentId + frameworkType are the required fields (see {@code requireRequired}).
     *
     * @param baseUrl the runtime base URL to set
     * @return a minimum-viable {@link PullRegistrationProperties.RuntimeEntry}
     */
    private static PullRegistrationProperties.RuntimeEntry baseRuntime(String baseUrl) {
        PullRegistrationProperties.RuntimeEntry runtime = new PullRegistrationProperties.RuntimeEntry();
        runtime.setBaseUrl(baseUrl);
        runtime.setTenantId("tenant-test");
        runtime.setAgentId("agent-test");
        runtime.setFrameworkType(FrameworkType.JIUWEN);
        return runtime;
    }

    /**
     * Direct package-private call to {@link PullRegistrationBootstrap#buildEntry}
     * — exercises the entry-construction logic without spinning up MockWebServer.
     *
     * @param runtime   the runtime entry to build from
     * @param agentName the agent name to stamp
     * @return the constructed {@link AgentRegistryEntry}
     */
    private static AgentRegistryEntry invokeBuildEntry(
            PullRegistrationProperties.RuntimeEntry runtime, String agentName) {
        return PullRegistrationBootstrap.buildEntry(runtime, agentName);
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
        public boolean delete(String tenantId, String agentId, String serviceId) {
            return false;
        }

        @Override
        public boolean delete(String tenantId, String agentId, String serviceId, String instanceId) {
            return false;
        }

        @Override
        public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            return List.of();
        }

        @Override
        public boolean updateStatus(AgentRegistryRepository.StatusUpdate update) {
            return false;
        }

        @Override
        public List<RegistryRow> listByAgentId(String tenantId, String agentId, String contractVersion) {
            return List.of();
        }

        @Override
        public List<RegistryRow> listByServiceId(String tenantId, String serviceId, String contractVersion) {
            return List.of();
        }

        @Override
        public List<RegistryRow> listByCapability(String tenantId, String capability, String contractVersion) {
            return List.of();
        }

        @Override
        public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId,
                                                    String serviceId, String instanceId) {
            return Optional.empty();
        }
    }
}
