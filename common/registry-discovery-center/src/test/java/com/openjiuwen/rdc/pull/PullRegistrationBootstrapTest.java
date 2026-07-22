/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.pull;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.AgentRegistryRepositoryStub;

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
 * @since 0.1.0 (2026)
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

    private static final class RecordingRepository extends AgentRegistryRepositoryStub {
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
        public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            return List.of();
        }
        @Override
        public Optional<EndpointEntry> findEndpoint(
                String tenantId, String agentId,
                String serviceId, String instanceId) {
            return Optional.empty();
        }
        @Override
        public List<DiscoveryRow> queryByTargetSelector(DiscoveryFilter filter) {
            return List.of();
        }
        @Override
        public void reconcileUpsert(ReconcileUpsertCommand command) {
        }

        @Override
        public List<InstanceKey> listInstanceKeysBySource(String sourceId) {
            return List.of();
        }
        @Override
        public void markDraining(String tenantId, String agentId, String serviceId) {
        }

        @Override
        public void markRemoved(String tenantId, String agentId, String serviceId) {
        }

        @Override
        public void markSourceStale(String sourceId) {
        }

        @Override
        public void markSourceFresh(String sourceId) {
        }

        @Override
        public List<InstanceKey> listDrainingPastGrace(java.time.Instant cutoff) {
            return List.of();
        }
        @Override
        public List<InstanceKey> listExpiredLeases(java.time.Instant now) {
            return List.of();
        }
        @Override
        public long getLastProcessedRevision(String sourceId) {
            return 0;
        }
        @Override
        public void updateLastProcessedRevision(String sourceId, long revision) {
        }

        @Override
        public void updateLastProcessedRevision(String sourceId, long revision, String snapshotFingerprint) {
        }

        @Override
        public java.util.Optional<String> getSnapshotFingerprint(String sourceId) {
            return java.util.Optional.empty();
        }
        @Override
        public java.util.Optional<String> findCardDigest(String tenantId, String agentId, String serviceId) {
            return java.util.Optional.empty();
        }
        @Override
        public void reconcilePending(ReconcilePendingCommand command) {
        }

        @Override
        public void markRefreshDegraded(String tenantId, String agentId, String serviceId) {
            }
        }
}
