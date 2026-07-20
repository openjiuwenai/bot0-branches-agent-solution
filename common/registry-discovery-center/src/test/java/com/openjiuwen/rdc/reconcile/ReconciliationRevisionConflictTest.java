/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.DeploymentInstanceObservation;
import com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult;
import com.openjiuwen.rdc.model.deployment.Readiness;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.AgentRegistryRepositoryStub;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Optional;

class ReconciliationRevisionConflictTest {

    private static final String SOURCE = "src-conflict";
    private static final long REVISION = 42L;

    @Test
    void same_revision_different_snapshot_returns_source_revision_gap() {
        TrackingRepository repository = new TrackingRepository();
        ReconciliationService service = service(repository);

        DeploymentInstanceObservation first = observation("http://10.0.0.1:8080");
        DeploymentInstanceObservation second = observation("http://10.0.0.2:8080");
        seedProcessedSnapshot(repository, List.of(first));

        ReconciliationService.ReconciliationResult conflict =
                service.reconcile(fixedProvider(List.of(second)));
        assertThat(conflict.success()).isFalse();
        assertThat(conflict.failureCode()).isEqualTo("SOURCE_REVISION_GAP");
        assertThat(repository.staleMarked.get()).isTrue();
    }

    @Test
    void same_revision_identical_snapshot_is_noop() {
        TrackingRepository repository = new TrackingRepository();
        ReconciliationService service = service(repository);

        DeploymentInstanceObservation obs = observation("http://10.0.0.1:8080");
        seedProcessedSnapshot(repository, List.of(obs));

        ReconciliationService.ReconciliationResult second =
                service.reconcile(fixedProvider(List.of(obs)));
        assertThat(second.success()).isTrue();
        assertThat(second.created()).isZero();
        assertThat(second.updated()).isZero();
        assertThat(repository.staleMarked.get()).isFalse();
    }

    @Test
    void conflict_emits_governance_metric() {
        TrackingRepository repository = new TrackingRepository();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        RegistryObservabilityConfig observability = new RegistryObservabilityConfig(meterRegistry);
        ReconciliationService service = new ReconciliationService(
                repository,
                new AgentCardFetcher(),
                new DeploymentDiscoveryProperties(),
                List.of(),
                observability);

        DeploymentInstanceObservation first = observation("http://10.0.0.1:8080");
        DeploymentInstanceObservation second = observation("http://10.0.0.2:8080");
        seedProcessedSnapshot(repository, List.of(first));
        service.reconcile(fixedProvider(List.of(second)));

        double conflicts = meterRegistry.find("agent_bus_registry_governance_total")
                .tag("event", "reconciliation_conflict")
                .counter()
                .count();
        assertThat(conflicts).isGreaterThanOrEqualTo(1.0);
    }

    private static void seedProcessedSnapshot(TrackingRepository repository,
                                                List<DeploymentInstanceObservation> observations) {
        ListDeploymentInstancesResult snapshot =
                new ListDeploymentInstancesResult(SOURCE, REVISION, observations);
        repository.lastRevision = REVISION;
        repository.fingerprint = Optional.of(SnapshotFingerprint.compute(snapshot));
    }

    private static ReconciliationService service(TrackingRepository repository) {
        return new ReconciliationService(
                repository,
                new AgentCardFetcher(),
                new DeploymentDiscoveryProperties(),
                List.of());
    }

    private static DeploymentDiscoveryProvider fixedProvider(List<DeploymentInstanceObservation> observations) {
        return new DeploymentDiscoveryProvider() {
            @Override
            public String sourceId() {
                return SOURCE;
            }

            @Override
            public ListDeploymentInstancesResult listInstances() {
                return new ListDeploymentInstancesResult(SOURCE, REVISION, observations);
            }
        };
    }

    private static DeploymentInstanceObservation observation(String baseUrl) {
        return new DeploymentInstanceObservation(
                "tenant-a",
                "svc-a",
                "inst-a",
                baseUrl,
                "1.0.0",
                Readiness.READY,
                SOURCE,
                REVISION,
                Instant.now());
    }

    private static final class TrackingRepository extends AgentRegistryRepositoryStub {
        long lastRevision;
        Optional<String> fingerprint = Optional.empty();
        final AtomicBoolean staleMarked = new AtomicBoolean(false);

        @Override public void upsert(com.openjiuwen.rdc.model.AgentRegistryEntry card, String json) { }
        @Override public boolean delete(String tenantId, String agentId) { return false; }
        @Override public boolean delete(String tenantId, String agentId, String serviceId) { return false; }
        @Override public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) { return List.of(); }
        @Override public List<DiscoveryRow> queryByTargetSelector(DiscoveryFilter filter) { return List.of(); }
        @Override public void reconcileUpsert(ReconcileUpsertCommand command) { }
        @Override public List<InstanceKey> listInstanceKeysBySource(String sourceId) { return List.of(); }
        @Override public void markDraining(String tenantId, String agentId, String serviceId) { }
        @Override public void markRemoved(String tenantId, String agentId, String serviceId) { }
        @Override public void markSourceStale(String sourceId) { staleMarked.set(true); }
        @Override public void markSourceFresh(String sourceId) { }
        @Override public List<InstanceKey> listDrainingPastGrace(Instant cutoff) { return List.of(); }
        @Override public List<InstanceKey> listExpiredLeases(Instant now) { return List.of(); }
        @Override public long getLastProcessedRevision(String sourceId) { return lastRevision; }
        @Override public void updateLastProcessedRevision(String sourceId, long revision) {
            updateLastProcessedRevision(sourceId, revision, null);
        }
        @Override public void updateLastProcessedRevision(String sourceId, long revision, String fp) {
            lastRevision = revision;
            fingerprint = fp != null ? Optional.of(fp) : Optional.empty();
        }
        @Override public Optional<String> getSnapshotFingerprint(String sourceId) { return fingerprint; }
        @Override public Optional<String> findCardDigest(String tenantId, String agentId, String serviceId) {
            return Optional.empty();
        }
        @Override public void reconcilePending(ReconcilePendingCommand command) { }
        @Override public void markRefreshDegraded(String tenantId, String agentId, String serviceId) { }
    }
}
