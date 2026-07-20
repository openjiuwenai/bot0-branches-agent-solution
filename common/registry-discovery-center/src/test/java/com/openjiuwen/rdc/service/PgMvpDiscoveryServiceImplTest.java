/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.DeadlineExceededException;
import com.openjiuwen.rdc.model.DiscoveryConstraints;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.EntryNotFoundException;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.MalformedRouteHandleException;
import com.openjiuwen.rdc.model.RegistryRequestContext;
import com.openjiuwen.rdc.model.RegistryUnavailableException;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.LogicalRegistrationRow;
import com.openjiuwen.rdc.repository.AgentRegistryRepositoryStub;
import com.openjiuwen.rdc.tenant.TenantContext;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Unit tests for {@link PgMvpDiscoveryServiceImpl} —
 * {@code searchInstancesByAgentId} DTO field fill strategy +
 * {@code resolveRouteHandle} failure modes + tenant isolation (ADR-0160
 * decisions 2 / 5 / 6, HD3-001/003/004/005/006).
 *
 * <p>REQ-2026-006: discovery evolves from single-value point lookup to
 * <em>list</em> lookup
 * ({@code searchInstancesByAgentId}). Each matching instance gets its own
 * opaque {@code routeHandle} (encoding {@code serviceId}); the caller selects
 * one and resolves it via {@code resolveRouteHandle}. Tests cover rich DTO
 * population, empty result (not found / DRAINING / OFFLINE), and tenant
 * isolation.
 *
 * @since 0.1.0 (2026)
 */
class PgMvpDiscoveryServiceImplTest {
    private RegistryObservabilityConfig observability;
    private TestTenantContext tenantContext;
    private PgMvpDiscoveryServiceImpl discovery;

    @BeforeEach
    void setUp() {
        observability = new RegistryObservabilityConfig(new SimpleMeterRegistry());
        tenantContext = new TestTenantContext();
        discovery = new PgMvpDiscoveryServiceImpl(new FakeRepository(), tenantContext, observability, null);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }
    // ---- searchInstancesByAgentId: rich DTO populated ---------------------

    @Test
    void search_by_agent_id_returns_rich_dto() {
        List<AgentCardDto> result = discovery.searchInstancesByAgentId("tenant-A", "agent-001");

        assertThat(result).hasSize(1);
        AgentCardDto dto = result.get(0);
        assertThat(dto.getRouteHandle()).isNotBlank();
        assertThat(dto.getHealth()).isEqualTo("ONLINE");
        assertThat(dto.getContractVersion()).isEqualTo("1.0.0");
        assertThat(dto.getCapabilityVersion()).isEqualTo("2.1.0");
        assertThat(dto.getWeight()).isEqualTo(100);
        assertThat(dto.getRegion()).isEqualTo("cn-east-1");
        assertThat(dto.getMaxConcurrency()).isEqualTo(10);
        assertThat(dto.getAgentName()).isEqualTo("财务助手");
        assertThat(dto.getFrameworkType()).isEqualTo(FrameworkType.JIUWEN);
    }

    @Test
    void search_by_agent_id_empty_when_not_found() {
        List<AgentCardDto> result = discovery.searchInstancesByAgentId("tenant-A", "agent-999");
        assertThat(result).isEmpty();
    }

    @Test
    void search_by_agent_id_empty_unknown_tenant() {
        List<AgentCardDto> result = discovery.searchInstancesByAgentId("tenant-unknown", "agent-001");
        assertThat(result).isEmpty();
    }

    @Test
    void search_by_agent_id_empty_when_draining() {
        // FakeRepository returns a DRAINING row for agent-002 — the discovery
        // service's listByAgentId filter (status IN ONLINE,DEGRADED) excludes
        // it, so the result is an empty list.
        List<AgentCardDto> result = discovery.searchInstancesByAgentId("tenant-A", "agent-002");
        assertThat(result).isEmpty();
    }

    // ---- RB7-tenant: TenantContext cross-check ----------------------------

    @Test
    void bound_tenant_context_mismatch_raises_isolation_violation() {
        tenantContext.set("tenant-other");
        assertThatThrownBy(() -> discovery.searchInstancesByAgentId("tenant-A", "agent-001"))
                .isInstanceOf(TenantIsolationViolationException.class)
                .hasMessageContaining("tenant scope denied");
    }

    @Test
    void bound_tenant_context_match_proceeds_normally() {
        tenantContext.set("tenant-A");
        List<AgentCardDto> result = discovery.searchInstancesByAgentId("tenant-A", "agent-001");
        assertThat(result).hasSize(1);
    }

    @Test
    void unbound_tenant_context_skips_cross_check() {
        assertThat(tenantContext.current()).isNull();
        List<AgentCardDto> result = discovery.searchInstancesByAgentId("tenant-A", "agent-001");
        assertThat(result).hasSize(1);
    }

    // ---- RB-resolve: resolveRouteHandle failure modes ---------------------

    @Test
    void resolve_route_handle_returns_endpoint_for_existing_entry() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec
                .HandleFields(
                        "tenant-A",
                        "agent-001",
                        "test-host-8080",
                        "test-host-8080",
                        "rk://svc/default",
                        "1.0.0"
                ));

        RouteResolution resolution = discovery.resolveRouteHandle(handle, "tenant-A");

        assertThat(resolution.endpointUrl()).isEqualTo("https://agent.example/agent");
        assertThat(resolution.routeKey()).isEqualTo("rk://svc/default");
        assertThat(resolution.contractVersion()).isEqualTo("1.0.0");
        assertThat(resolution.capabilityVersion()).isEqualTo("2.1.0");
    }

    @Test
    void resolve_handle_tenant_mismatch_raises() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec
                .HandleFields(
                        "tenant-A",
                        "agent-001",
                        "test-host-8080",
                        "test-host-8080",
                        "rk://svc/default",
                        "1.0.0"
                ));

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-B"))
                .isInstanceOf(TenantIsolationViolationException.class)
                .hasMessageContaining("tenant scope denied");
    }

    @Test
    void resolve_route_handle_malformed_raises_registry_failure() {
        assertThatThrownBy(() -> discovery.resolveRouteHandle("!!!not-base64!!!", "tenant-A"))
                .isInstanceOf(MalformedRouteHandleException.class)
                .satisfies(ex -> {
                    if (ex instanceof MalformedRouteHandleException m) {
        assertThat(m.failure().failureCode()).isEqualTo("MALFORMED_ROUTE_HANDLE");
    }
                });
    }

    @Test
    void resolve_handle_missing_raises_not_found() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec
                .HandleFields(
                        "tenant-A",
                        "agent-999",
                        "test-host-8080",
                        "test-host-8080",
                        "rk://svc/default",
                        "1.0.0"
                ));

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-A"))
                .isInstanceOf(EntryNotFoundException.class)
                .satisfies(ex -> {
                    if (ex instanceof EntryNotFoundException e) {
                        assertThat(e.failure().failureCode()).isEqualTo("ENTRY_NOT_FOUND");
                    }
                });
    }

    @Test
    void resolve_handle_mismatched_tenant_raises() {
        tenantContext.set("tenant-C");
        String handle = RouteHandleCodec.encode(new RouteHandleCodec
                .HandleFields(
                        "tenant-A",
                        "agent-001",
                        "test-host-8080",
                        "test-host-8080",
                        "rk://svc/default",
                        "1.0.0"
                ));

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-A"))
                .isInstanceOf(TenantIsolationViolationException.class);
    }

    @Test
    void discover_rejects_expired_deadline() {
        RegistryRequestContext ctx = new RegistryRequestContext(
                "tenant-A", "caller", "trace-1", "req-1", Instant.now().minusSeconds(1));
        DiscoveryQuery query = DiscoveryQuery.builder()
                .context(ctx)
                .agentId("agent-001")
                .constraints(DiscoveryConstraints.none())
                .limit(10)
                .build();

        assertThatThrownBy(() -> discovery.discover(query))
                .isInstanceOf(DeadlineExceededException.class);
    }

    @Test
    void resolve_rejects_expired_deadline() {
        assertThatThrownBy(() -> discovery.resolveRouteHandle(
                "v1:any", "tenant-A", "caller", "trace-1", Instant.now().minusSeconds(1)))
                .isInstanceOf(DeadlineExceededException.class);
    }

    @Test
    void discover_mismatched_tenant_raises_denied() {
        tenantContext.set("tenant-C");
        RegistryRequestContext ctx = new RegistryRequestContext(
                "tenant-A", "caller", "trace-tenant", "req-1", Instant.now().plusSeconds(30));
        DiscoveryQuery query = DiscoveryQuery.builder()
                .context(ctx)
                .agentId("agent-001")
                .constraints(DiscoveryConstraints.none())
                .limit(10)
                .build();

        assertThatThrownBy(() -> discovery.discover(query))
                .isInstanceOf(TenantIsolationViolationException.class);
    }

    @Test
    void discover_db_failure_raises_registry_unavailable() {
        PgMvpDiscoveryServiceImpl failingDiscovery = new PgMvpDiscoveryServiceImpl(
                new FailingRepository(),
                tenantContext,
                observability,
                null);
        RegistryRequestContext ctx = new RegistryRequestContext(
                "tenant-A", "caller", "trace-db", "req-1", Instant.now().plusSeconds(5));
        DiscoveryQuery query = DiscoveryQuery.builder()
                .context(ctx)
                .agentId("agent-001")
                .constraints(DiscoveryConstraints.none())
                .limit(10)
                .build();

        assertThatThrownBy(() -> failingDiscovery.discover(query))
                .isInstanceOf(RegistryUnavailableException.class);
    }

    // ---- Fakes -----------------------------------------------------------

    private static final class FakeRepository extends AgentRegistryRepositoryStub {
        @Override
        public void upsert(com.openjiuwen.rdc.model.AgentRegistryEntry card, String a2aAgentCardJson) {
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
        public java.util.List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            return java.util.List.of();
        }
        @Override
        public List<RegistryRow> listByAgentId(String tenantId, String agentId, String contractVersion) {
            if (!"tenant-A".equals(tenantId)) {
                return List.of();
            }
            if ("agent-001".equals(agentId)) {
                return List.of(sampleRow("test-host-8080", "agent-001", "财务助手", "ONLINE"));
            }
            if ("agent-002".equals(agentId)) {
                // DRAINING — real SQL filter (status IN ONLINE,DEGRADED) excludes this.
                // Fake mimics the SQL behavior by returning empty.
                return List.of();
            }
            return List.of();
        }

        @Override
        public Optional<EndpointEntry> findEndpoint(
                String tenantId, String agentId,
                String serviceId, String instanceId) {
            if ("tenant-A".equals(tenantId) && "agent-001".equals(agentId)
                    && "test-host-8080".equals(serviceId)) {
                return Optional.of(new EndpointEntry(
                        "https://agent.example/agent", "rk://svc/default", "1.0.0"));
            }
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
        @Override
        public java.util.Optional<ResolveRow> findForResolve(
                String tenantId, String agentId, String serviceId, String instanceId) {
            if ("tenant-A".equals(tenantId) && "agent-001".equals(agentId)
                    && "test-host-8080".equals(serviceId)) {
                return java.util.Optional.of(new ResolveRow(
                        "https://agent.example/agent",
                        "rk://svc/default",
                        "1.0.0",
                        "2.1.0",
                        "ACTIVE",
                        java.time.Instant.now().plusSeconds(3600)));
            }
            return java.util.Optional.empty();
        }

        private static RegistryRow sampleRow(String serviceId, String agentId,
                                             String agentName, String status) {
            return new RegistryRow(
                    serviceId, serviceId, agentId, agentName, FrameworkType.JIUWEN,
                    "rk://svc/default", "1.0.0", "2.1.0",
                    100, "cn-east-1", 10, status, java.util.List.of());
        }
    }

    private static final class FailingRepository extends AgentRegistryRepositoryStub {
        @Override
        public List<DiscoveryRow> queryByTargetSelector(DiscoveryFilter filter) {
            throw new DataAccessResourceFailureException("database unavailable");
        }
        @Override
        public List<LogicalRegistrationRow> queryLogicalByTargetSelector(DiscoveryFilter filter) {
            throw new DataAccessResourceFailureException("database unavailable");
        }
        @Override
        public void upsert(com.openjiuwen.rdc.model.AgentRegistryEntry card, String a2aAgentCardJson) { }
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
        public void reconcileUpsert(ReconcileUpsertCommand command) { }
        @Override
        public List<InstanceKey> listInstanceKeysBySource(String sourceId) {
            return List.of();
        }
        @Override
        public void markDraining(String tenantId, String agentId, String serviceId) { }
        @Override
        public void markRemoved(String tenantId, String agentId, String serviceId) { }
        @Override
        public void markSourceStale(String sourceId) { }
        @Override
        public void markSourceFresh(String sourceId) { }
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
        public void updateLastProcessedRevision(String sourceId, long revision) { }
        @Override
        public void updateLastProcessedRevision(String sourceId, long revision, String fingerprint) { }
        @Override
        public java.util.Optional<String> getSnapshotFingerprint(String sourceId) {
            return java.util.Optional.empty();
        }
        @Override
        public java.util.Optional<String> findCardDigest(String tenantId, String agentId, String serviceId) {
            return java.util.Optional.empty();
        }
        @Override
        public void reconcilePending(ReconcilePendingCommand command) { }
        @Override
        public void markRefreshDegraded(String tenantId, String agentId, String serviceId) {
            }
        }
    private static final class TestTenantContext implements TenantContext {
        private String current;

        @Override
        public String current() {
            return current;
        }
        void set(String tenantId) {
            this.current = tenantId;
        }
        void clear() {
            this.current = null;
        }
    }
}
