/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import com.openjiuwen.rdc.model.AgentRegistryEntry;

import java.util.List;
import java.util.Optional;

/**
 * No-op stub for tests — override only the methods under test.
 *
 * @since 0.1.0 (2026)
 */
public class AgentRegistryRepositoryStub implements AgentRegistryRepository {

    @Override
    public void upsert(AgentRegistryEntry entry, String a2aAgentCardJson) {
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
    public boolean updateStatus(StatusUpdate update) {
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
    public Optional<EndpointEntry> findEndpoint(
            String tenantId, String agentId, String serviceId, String instanceId) {
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
    public Optional<String> getSnapshotFingerprint(String sourceId) {
        return Optional.empty();
    }

    @Override
    public Optional<String> findCardDigest(String tenantId, String agentId, String serviceId) {
        return Optional.empty();
    }

    @Override
    public void reconcilePending(ReconcilePendingCommand command) {
    }

    @Override
    public void markRefreshDegraded(String tenantId, String agentId, String serviceId) {
    }

    @Override
    public Optional<ResolveRow> findForResolve(
            String tenantId, String agentId, String serviceId, String instanceId) {
        return Optional.empty();
    }
}
