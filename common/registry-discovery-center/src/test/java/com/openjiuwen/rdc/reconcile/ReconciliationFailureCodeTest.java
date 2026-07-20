/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.reconcile;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.deployment.DeploymentDiscoveryProperties;
import com.openjiuwen.rdc.model.deployment.DeploymentDiscoveryProvider;
import com.openjiuwen.rdc.model.deployment.ListDeploymentInstancesResult;
import com.openjiuwen.rdc.model.deployment.SourceRevisionGapException;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.AgentRegistryRepositoryStub;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * ReconciliationFailureCodeTest coverage.
 *
 * @since 0.1.0 (2026)
 */
class ReconciliationFailureCodeTest {
    @Test
    void revision_gap_maps_to_source_revision_gap_failure_code() {
        ReconciliationService service = service(new StubRepository(5L));
        DeploymentDiscoveryProvider provider = gapProvider();

        ReconciliationService.ReconciliationResult result = service.reconcile(provider);

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("SOURCE_REVISION_GAP");
    }

    @Test
    void runtime_exception_maps_to_deployment_source_unavailable() {
        ReconciliationService service = service(new StubRepository(0L));
        DeploymentDiscoveryProvider provider = failingProvider();

        ReconciliationService.ReconciliationResult result = service.reconcile(provider);

        assertThat(result.success()).isFalse();
        assertThat(result.failureCode()).isEqualTo("DEPLOYMENT_SOURCE_UNAVAILABLE");
    }

    @Test
    void source_unavailable_marks_regs_stale() {
        StubRepository repository = new StubRepository(0L);
        ReconciliationService service = service(repository);
        service.reconcile(failingProvider());
        assertThat(repository.logicalStaleMarked).isTrue();
    }

    private static ReconciliationService service(AgentRegistryRepository repository) {
        DeploymentDiscoveryProperties properties = new DeploymentDiscoveryProperties();
        return new ReconciliationService(
                repository,
                new AgentCardFetcher(),
                properties,
                List.of());
    }

    private static DeploymentDiscoveryProvider gapProvider() {
        return new DeploymentDiscoveryProvider() {
            @Override
            public String sourceId() {
                return "src-gap";
            }

            @Override
            public ListDeploymentInstancesResult listInstances() {
                throw new SourceRevisionGapException("src-gap", "revision jumped backwards");
            }
        };
    }

    private static DeploymentDiscoveryProvider failingProvider() {
        return new DeploymentDiscoveryProvider() {
            @Override
            public String sourceId() {
                return "src-down";
            }

            @Override
            public ListDeploymentInstancesResult listInstances() {
                throw new TestProbeException("connection refused");
            }
        };
    }

    private static final class StubRepository extends AgentRegistryRepositoryStub {
        boolean logicalStaleMarked;
        private final long lastRevision;

        StubRepository(long lastRevision) {
            this.lastRevision = lastRevision;
        }

        @Override
        public void upsert(com.openjiuwen.rdc.model.AgentRegistryEntry card, String json) {
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
        public java.util.Optional<EndpointEntry> findEndpoint(
                String tenantId, String agentId,
                String serviceId, String instanceId) {
            return java.util.Optional.empty();
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
            logicalStaleMarked = true;
            markLogicalRegistrationsStaleSource(sourceId);
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
            return lastRevision;
        }

        @Override
        public void updateLastProcessedRevision(String sourceId, long revision) {
        }

        @Override
        public void updateLastProcessedRevision(String sourceId, long revision, String fingerprint) {
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

    private static final class TestProbeException extends RuntimeException {
        TestProbeException(String message) {
            super(message);
        }
    }
}
