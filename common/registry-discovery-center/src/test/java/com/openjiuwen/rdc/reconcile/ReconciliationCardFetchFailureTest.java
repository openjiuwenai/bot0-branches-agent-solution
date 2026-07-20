/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.deployment.StaticDeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.AgentRegistryRepositoryStub;
import com.openjiuwen.rdc.security.RdcCardFetchOptions;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Card fetch failure codes during reconciliation (0713 §5.1.6).
 */
class ReconciliationCardFetchFailureTest {

    private MockWebServer runtimeServer;

    @BeforeEach
    void startServer() throws Exception {
        runtimeServer = new MockWebServer();
        runtimeServer.start();
    }

    @AfterEach
    void stopServer() throws Exception {
        if (runtimeServer != null) {
            runtimeServer.shutdown();
        }
    }

    @Test
    void invalid_card_schema_records_pending_not_registered() {
        runtimeServer.enqueue(new MockResponse()
                .setBody("{\"name\":\"only-name\"}")
                .addHeader("Content-Type", "application/json"));

        RecordingRepository repository = new RecordingRepository();
        ReconciliationService service = new ReconciliationService(
                repository,
                new AgentCardFetcher(),
                new DeploymentDiscoveryProperties(),
                List.of(binding()));

        var result = service.reconcile(provider());

        assertThat(result.success()).isTrue();
        assertThat(repository.upserted).isFalse();
        assertThat(repository.pending).isTrue();
    }

    @Test
    void signature_invalid_records_pending_not_registered() throws Exception {
        runtimeServer.enqueue(new MockResponse()
                .setBody(validUnsignedCard())
                .addHeader("Content-Type", "application/json"));

        KeyPair keyPair = testKeyPair();
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.setVerifySignatures(true);
        props.setSignerPemsByKid(Map.of("test-key", publicKeyPem(keyPair)));

        RecordingRepository repository = new RecordingRepository();
        ReconciliationService service = new ReconciliationService(
                repository,
                AgentCardFetcher.fromSecurity(props),
                new DeploymentDiscoveryProperties(),
                List.of(binding()));

        var result = service.reconcile(provider());

        assertThat(result.success()).isTrue();
        assertThat(repository.upserted).isFalse();
        assertThat(repository.pending).isTrue();
    }

    private StaticDeploymentDiscoveryProvider provider() {
        return new StaticDeploymentDiscoveryProvider(List.of(
                new StaticDeploymentDiscoveryProvider.StaticInstanceConfig(
                        "tenant-fetch", "billing-svc", "pod-0",
                        baseUrl(), "1.0.0", Readiness.READY)));
    }

    private static ReconciliationService.StaticInstanceRuntimeBinding binding() {
        return new ReconciliationService.StaticInstanceRuntimeBinding(
                "tenant-fetch", "billing-svc", "pod-0",
                FrameworkType.JIUWEN, "/v1/query", "1.0.0", "1.0.0",
                "/.well-known/agent-card.json", Map.of(), 10, 100, "cn-east-1");
    }

    private String baseUrl() {
        return runtimeServer.url("/").toString().replaceAll("/$", "");
    }

    private static String validUnsignedCard() {
        return "{"
                + "\"name\":\"billing-agent\","
                + "\"description\":\"billing\","
                + "\"version\":\"1.0.0\","
                + "\"defaultInputModes\":[\"text\"],"
                + "\"defaultOutputModes\":[\"text\"],"
                + "\"capabilities\":{\"streaming\":true},"
                + "\"skills\":[],"
                + "\"supportedInterfaces\":[{\"protocol\":\"jsonrpc\",\"url\":\"/a2a\",\"version\":\"1.0.0\"}]"
                + "}";
    }

    private static KeyPair testKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String publicKeyPem(KeyPair keyPair) {
        String encoded = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    private static final class RecordingRepository extends AgentRegistryRepositoryStub {
        boolean upserted;
        boolean pending;

        @Override
        public void reconcileUpsert(ReconcileUpsertCommand command) {
            upserted = true;
        }

        @Override
        public void reconcilePending(ReconcilePendingCommand command) {
            pending = true;
        }

        @Override public void upsert(com.openjiuwen.rdc.model.AgentRegistryEntry card, String json) { }
        @Override public boolean delete(String tenantId, String agentId) { return false; }
        @Override public boolean delete(String tenantId, String agentId, String serviceId) { return false; }
        @Override public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) { return List.of(); }
        @Override public java.util.Optional<EndpointEntry> findEndpoint(String tenantId, String agentId, String serviceId, String instanceId) {
            return java.util.Optional.empty();
        }
        @Override public List<DiscoveryRow> queryByTargetSelector(DiscoveryFilter filter) { return List.of(); }
        @Override public List<InstanceKey> listInstanceKeysBySource(String sourceId) { return List.of(); }
        @Override public void markDraining(String tenantId, String agentId, String serviceId) { }
        @Override public void markRemoved(String tenantId, String agentId, String serviceId) { }
        @Override public void markSourceStale(String sourceId) { }
        @Override public void markSourceFresh(String sourceId) { }
        @Override public List<InstanceKey> listDrainingPastGrace(java.time.Instant cutoff) { return List.of(); }
        @Override public List<InstanceKey> listExpiredLeases(java.time.Instant now) { return List.of(); }
        @Override public long getLastProcessedRevision(String sourceId) { return 0; }
        @Override public void updateLastProcessedRevision(String sourceId, long revision) { }
        @Override public void updateLastProcessedRevision(String sourceId, long revision, String fingerprint) { }
        @Override public java.util.Optional<String> getSnapshotFingerprint(String sourceId) { return java.util.Optional.empty(); }
        @Override public java.util.Optional<String> findCardDigest(String tenantId, String agentId, String serviceId) {
            return java.util.Optional.empty();
        }
        @Override public void markRefreshDegraded(String tenantId, String agentId, String serviceId) { }
    }
}
