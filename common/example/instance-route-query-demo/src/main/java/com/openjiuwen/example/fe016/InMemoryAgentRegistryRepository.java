/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.fe016;

import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内 {@link AgentRegistryRepository} 实现 — 仅用于 FEAT-016 AgentDemo
 * 演示，预置多租户 / 多实例 / 多能力的种子数据，让 demo 无需 PostgreSQL +
 * Flyway 即可启动并对 {@code /api/registry/instances/**} 与
 * {@code /route-handle/resolve} 做端到端验证。
 *
 * <p>仅实现 FEAT-016 相关查询 / 解析方法（{@link #listByAgentId} /
 * {@link #listByServiceId} / {@link #listByCapability} /
 * {@link #findForResolve} / {@link #findEndpoint} / {@link #delete}）；
 * Feat-015 对账 / 调度相关方法返回空 / false 占位，因为 demo 不触发它们。
 *
 * @since 0.1.0 (2026)
 */
public final class InMemoryAgentRegistryRepository implements AgentRegistryRepository {

    private final List<Seed> store = new CopyOnWriteArrayList<>();

    public InMemoryAgentRegistryRepository() {
        seed();
    }

    private void seed() {
        Instant farFuture = Instant.now().plusSeconds(3600);
        store.add(new Seed("tenant-A", "agent-001", "svc-001", "10.0.0.1:8090", "demo-query-agent",
                FrameworkType.JIUWEN, "/v1/query", "1.0.0", "1.2.0",
                100, "cn-east", 10, "ONLINE",
                List.of("query", "summarize"), "http://127.0.0.1:18090",
                "ACTIVE", farFuture));
        store.add(new Seed("tenant-A", "agent-001", "svc-001", "10.0.0.2:8090", "demo-query-agent",
                FrameworkType.JIUWEN, "/v1/query", "1.0.0", "1.2.0",
                90, "cn-east", 8, "ONLINE",
                List.of("query"), "http://10.0.0.2:8090",
                "ACTIVE", farFuture));
        store.add(new Seed("tenant-A", "agent-001", "svc-002", "10.0.0.3:8090", "demo-chat-agent",
                FrameworkType.VERSATILE, "/v1/chat", "2.0.0", "2.0.0",
                80, "cn-east", 12, "ONLINE",
                List.of("chat"), "http://10.0.0.3:8090",
                "ACTIVE", farFuture));
        store.add(new Seed("tenant-A", "agent-002", "svc-003", "10.0.0.4:8090", "demo-translate-agent",
                FrameworkType.AGENTSCOPE, "/v1/translate", "1.1.0", "1.0.0",
                50, "cn-west", 5, "DEGRADED",
                List.of("translate"), "http://10.0.0.4:8090",
                "ACTIVE", farFuture));
        store.add(new Seed("tenant-B", "agent-001", "svc-001", "10.0.1.1:8090", "demo-query-agent",
                FrameworkType.JIUWEN, "/v1/query", "1.0.0", "1.2.0",
                100, "cn-east", 10, "ONLINE",
                List.of("query", "summarize"), "http://10.0.1.1:8090",
                "ACTIVE", farFuture));
    }

    @Override
    public List<RegistryRow> listByAgentId(String tenantId, String agentId, String contractVersion) {
        return store.stream()
                .filter(s -> s.tenantId.equals(tenantId) && s.agentId.equals(agentId))
                .filter(s -> contractVersion == null || contractVersion.equals(s.contractVersion))
                .filter(s -> isVisible(s.status))
                .sorted(Comparator.comparingInt((Seed s) -> s.weight).reversed())
                .map(InMemoryAgentRegistryRepository::toRegistryRow)
                .toList();
    }

    @Override
    public List<RegistryRow> listByServiceId(String tenantId, String serviceId, String contractVersion) {
        return store.stream()
                .filter(s -> s.tenantId.equals(tenantId) && s.serviceId.equals(serviceId))
                .filter(s -> contractVersion == null || contractVersion.equals(s.contractVersion))
                .filter(s -> isVisible(s.status))
                .sorted(Comparator.comparingInt((Seed s) -> s.weight).reversed())
                .map(InMemoryAgentRegistryRepository::toRegistryRow)
                .toList();
    }

    @Override
    public List<RegistryRow> listByCapability(String tenantId, String capability, String contractVersion) {
        return store.stream()
                .filter(s -> s.tenantId.equals(tenantId) && s.capabilities.contains(capability))
                .filter(s -> contractVersion == null || contractVersion.equals(s.contractVersion))
                .filter(s -> isVisible(s.status))
                .sorted(Comparator.comparingInt((Seed s) -> s.weight).reversed())
                .map(InMemoryAgentRegistryRepository::toRegistryRow)
                .toList();
    }

    @Override
    public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId,
                                                 String serviceId, String instanceId) {
        return store.stream()
                .filter(s -> s.tenantId.equals(tenantId) && s.agentId.equals(agentId)
                        && s.serviceId.equals(serviceId) && s.instanceId.equals(instanceId))
                .findFirst()
                .map(s -> new EndpointEntry(s.endpointUrl, s.routeKey, s.contractVersion));
    }

    @Override
    public Optional<ResolveRow> findForResolve(String tenantId, String agentId,
                                                String serviceId, String instanceId) {
        return store.stream()
                .filter(s -> s.tenantId.equals(tenantId) && s.agentId.equals(agentId)
                        && s.serviceId.equals(serviceId) && s.instanceId.equals(instanceId))
                .findFirst()
                .map(s -> new ResolveRow(s.endpointUrl, s.routeKey, s.contractVersion,
                        s.capabilityVersion, s.lifecycleStatus, s.leaseExpiresAt));
    }

    @Override
    public boolean delete(String tenantId, String agentId) {
        int before = store.size();
        store.removeIf(s -> s.tenantId.equals(tenantId) && s.agentId.equals(agentId));
        return store.size() < before;
    }

    @Override
    public boolean delete(String tenantId, String agentId, String serviceId) {
        int before = store.size();
        store.removeIf(s -> s.tenantId.equals(tenantId) && s.agentId.equals(agentId)
                && s.serviceId.equals(serviceId));
        return store.size() < before;
    }

    @Override
    public boolean delete(String tenantId, String agentId, String serviceId, String instanceId) {
        int before = store.size();
        store.removeIf(s -> s.tenantId.equals(tenantId) && s.agentId.equals(agentId)
                && s.serviceId.equals(serviceId) && s.instanceId.equals(instanceId));
        return store.size() < before;
    }

    @Override
    public boolean updateStatus(StatusUpdate update) {
        for (Seed s : store) {
            if (s.tenantId.equals(update.tenantId()) && s.agentId.equals(update.agentId())
                    && s.serviceId.equals(update.serviceId())
                    && s.instanceId.equals(update.instanceId())) {
                store.set(store.indexOf(s), s.withStatus(update.newStatus()));
                return true;
            }
        }
        return false;
    }

    @Override
    public void upsert(AgentRegistryEntry entry, String a2aAgentCardJson) {
        // demo 不暴露 POST /register 端点，无需实现 upsert 语义
    }

    @Override
    public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
        return List.of();
    }

    @Override
    public List<DiscoveryRow> queryByTargetSelector(DiscoveryFilter filter) {
        return List.of();
    }

    @Override
    public void reconcileUpsert(ReconcileUpsertCommand command) {
        // Feat-015 对账 — demo 不触发
    }

    @Override
    public List<InstanceKey> listInstanceKeysBySource(String sourceId) {
        return List.of();
    }

    @Override
    public void markDraining(String tenantId, String agentId, String serviceId) {
        // Feat-015 — demo 不触发
    }

    @Override
    public void markRemoved(String tenantId, String agentId, String serviceId) {
        // Feat-015 — demo 不触发
    }

    @Override
    public void markSourceStale(String sourceId) {
        // Feat-015 — demo 不触发
    }

    @Override
    public void markSourceFresh(String sourceId) {
        // Feat-015 — demo 不触发
    }

    @Override
    public List<InstanceKey> listDrainingPastGrace(Instant cutoff) {
        return List.of();
    }

    @Override
    public List<InstanceKey> listExpiredLeases(Instant now) {
        return List.of();
    }

    @Override
    public long getLastProcessedRevision(String sourceId) {
        return 0L;
    }

    @Override
    public void updateLastProcessedRevision(String sourceId, long revision) {
        // Feat-015 — demo 不触发
    }

    @Override
    public void updateLastProcessedRevision(String sourceId, long revision, String snapshotFingerprint) {
        // Feat-015 — demo 不触发
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
        // Feat-015 — demo 不触发
    }

    @Override
    public void markRefreshDegraded(String tenantId, String agentId, String serviceId) {
        // Feat-015 — demo 不触发
    }

    private static boolean isVisible(String status) {
        // 发现层排除 DRAINING / OFFLINE（设计文档 L2 §4.4）
        return "ONLINE".equals(status) || "DEGRADED".equals(status);
    }

    private static RegistryRow toRegistryRow(Seed s) {
        return new RegistryRow(
                s.serviceId, s.instanceId, s.agentId, s.agentName, s.frameworkType,
                s.routeKey, s.contractVersion, s.capabilityVersion,
                s.weight, s.region, s.maxConcurrency, s.status,
                new ArrayList<>(s.capabilities));
    }

    private record Seed(
            String tenantId, String agentId, String serviceId, String instanceId,
            String agentName, FrameworkType frameworkType, String routeKey,
            String contractVersion, String capabilityVersion,
            int weight, String region, int maxConcurrency, String status,
            List<String> capabilities, String endpointUrl,
            String lifecycleStatus, Instant leaseExpiresAt) {

        Seed withStatus(String newStatus) {
            return new Seed(tenantId, agentId, serviceId, instanceId, agentName,
                    frameworkType, routeKey, contractVersion, capabilityVersion,
                    weight, region, maxConcurrency, newStatus,
                    capabilities, endpointUrl, lifecycleStatus, leaseExpiresAt);
        }
    }
}
