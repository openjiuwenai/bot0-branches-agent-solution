package com.openjiuwen.rdc.registry.runtime.discovery;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.spi.registry.AgentCardDto;
import com.openjiuwen.rdc.spi.registry.FrameworkType;
import com.openjiuwen.rdc.spi.registry.RouteResolution;
import com.openjiuwen.rdc.spi.registry.TenantContext;
import com.openjiuwen.rdc.spi.registry.TenantIsolationViolationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PgMvpDiscoveryServiceImpl} — {@code searchByAgentId}
 * DTO field fill strategy + {@code resolveRouteHandle} failure modes +
 * tenant isolation (ADR-0160 decisions 2 / 5 / 6, HD3-001/003/004/005/006).
 *
 * <p>REQ-2026-004: dual Method A/B discovery replaced by single-value
 * {@code searchByAgentId} point lookup. Tests cover rich DTO population,
 * empty result (not found / DRAINING / OFFLINE), and tenant isolation.
 */
class PgMvpDiscoveryServiceImplTest {

    private RegistryObservabilityConfig observability;
    private TestTenantContext tenantContext;
    private PgMvpDiscoveryServiceImpl discovery;

    @BeforeEach
    void setUp() {
        observability = new RegistryObservabilityConfig(new SimpleMeterRegistry());
        tenantContext = new TestTenantContext();
        discovery = new PgMvpDiscoveryServiceImpl(new FakeRepository(), tenantContext, observability);
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // ---- searchByAgentId: rich DTO populated ------------------------------

    @Test
    void search_by_agent_id_returns_rich_dto_with_all_fields_populated() {
        Optional<AgentCardDto> result = discovery.searchByAgentId("tenant-A", "agent-001");

        assertThat(result).isPresent();
        AgentCardDto dto = result.get();
        assertThat(dto.getRouteHandle()).isNotBlank();
        assertThat(dto.getHealth()).isEqualTo("ONLINE");
        assertThat(dto.getContractVersion()).isEqualTo("1.0.0");
        assertThat(dto.getCapabilityVersion()).isEqualTo("2.1.0");
        assertThat(dto.getWeight()).isEqualTo(100);
        assertThat(dto.getRegion()).isEqualTo("cn-east-1");
        assertThat(dto.getAgentName()).isEqualTo("财务助手");
        assertThat(dto.getFrameworkType()).isEqualTo(FrameworkType.JIUWEN);
    }

    @Test
    void search_by_agent_id_returns_empty_when_not_found() {
        Optional<AgentCardDto> result = discovery.searchByAgentId("tenant-A", "agent-999");
        assertThat(result).isEmpty();
    }

    @Test
    void search_by_agent_id_returns_empty_for_unknown_tenant() {
        Optional<AgentCardDto> result = discovery.searchByAgentId("tenant-unknown", "agent-001");
        assertThat(result).isEmpty();
    }

    @Test
    void search_by_agent_id_returns_empty_when_status_is_draining() {
        // FakeRepository returns a DRAINING row for agent-002.
        Optional<AgentCardDto> result = discovery.searchByAgentId("tenant-A", "agent-002");
        assertThat(result).isEmpty();
    }

    // ---- RB7-tenant: TenantContext cross-check ----------------------------

    @Test
    void bound_tenant_context_mismatch_raises_isolation_violation() {
        tenantContext.set("tenant-other");
        assertThatThrownBy(() -> discovery.searchByAgentId("tenant-A", "agent-001"))
                .isInstanceOf(TenantIsolationViolationException.class)
                .hasMessageContaining("tenant_isolation_violation");
    }

    @Test
    void bound_tenant_context_match_proceeds_normally() {
        tenantContext.set("tenant-A");
        Optional<AgentCardDto> result = discovery.searchByAgentId("tenant-A", "agent-001");
        assertThat(result).isPresent();
    }

    @Test
    void unbound_tenant_context_skips_cross_check() {
        assertThat(tenantContext.current()).isNull();
        Optional<AgentCardDto> result = discovery.searchByAgentId("tenant-A", "agent-001");
        assertThat(result).isPresent();
    }

    // ---- RB-resolve: resolveRouteHandle failure modes ---------------------

    @Test
    void resolve_route_handle_returns_endpoint_for_existing_entry() {
        String handle = RouteHandleCodec.encode(
                "tenant-A", "agent-001", "rk://svc/default", "1.0.0");

        RouteResolution resolution = discovery.resolveRouteHandle(handle, "tenant-A");

        assertThat(resolution.endpointUrl()).isEqualTo("https://agent.example/agent");
        assertThat(resolution.routeKey()).isEqualTo("rk://svc/default");
        assertThat(resolution.contractVersion()).isEqualTo("1.0.0");
    }

    @Test
    void resolve_route_handle_tenant_mismatch_raises_isolation_violation() {
        String handle = RouteHandleCodec.encode(
                "tenant-A", "agent-001", "rk://svc/default", "1.0.0");

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-B"))
                .isInstanceOf(TenantIsolationViolationException.class)
                .hasMessageContaining("tenant_isolation_violation");
    }

    @Test
    void resolve_route_handle_malformed_raises_illegal_argument() {
        assertThatThrownBy(() -> discovery.resolveRouteHandle("!!!not-base64!!!", "tenant-A"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_route_handle_nonexistent_entry_raises_no_such_element() {
        String handle = RouteHandleCodec.encode(
                "tenant-A", "agent-999", "rk://svc/default", "1.0.0");

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-A"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("entry_not_found");
    }

    @Test
    void resolve_route_handle_with_bound_mismatched_tenant_raises_isolation_violation() {
        tenantContext.set("tenant-C");
        String handle = RouteHandleCodec.encode(
                "tenant-A", "agent-001", "rk://svc/default", "1.0.0");

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-A"))
                .isInstanceOf(TenantIsolationViolationException.class);
    }

    // ---- Fakes -----------------------------------------------------------

    private static final class FakeRepository implements AgentRegistryRepository {
        @Override
        public void upsert(com.openjiuwen.rdc.spi.registry.AgentRegistryEntry card, String a2aAgentCardJson) {
        }

        @Override
        public boolean delete(String tenantId, String agentId) {
            return false;
        }

        @Override
        public java.util.List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            return java.util.List.of();
        }

        @Override
        public boolean updateStatus(String tenantId, String agentId,
                                    String newStatus, boolean refreshHeartbeat) {
            return false;
        }

        @Override
        public Optional<RegistryRow> searchByAgentId(String tenantId, String agentId) {
            if (!"tenant-A".equals(tenantId)) {
                return Optional.empty();
            }
            if ("agent-001".equals(agentId)) {
                return Optional.of(sampleRow("agent-001", "财务助手", "ONLINE"));
            }
            if ("agent-002".equals(agentId)) {
                // DRAINING — real SQL filter (status IN ONLINE,DEGRADED) excludes this.
                // Fake mimics the SQL behavior by returning empty.
                return Optional.empty();
            }
            return Optional.empty();
        }

        @Override
        public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId) {
            if ("tenant-A".equals(tenantId) && "agent-001".equals(agentId)) {
                return Optional.of(new EndpointEntry(
                        "https://agent.example/agent", "rk://svc/default", "1.0.0"));
            }
            return Optional.empty();
        }

        private static RegistryRow sampleRow(String agentId, String agentName, String status) {
            return new RegistryRow(
                    agentId, agentName, FrameworkType.JIUWEN,
                    "rk://svc/default", "1.0.0", "2.1.0",
                    100, "cn-east-1", status);
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
