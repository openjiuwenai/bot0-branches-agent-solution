package com.openjiuwen.rdc.registry.runtime.discovery;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.spi.registry.AgentCardDto;
import com.openjiuwen.rdc.spi.registry.RouteResolution;
import com.openjiuwen.rdc.spi.registry.TenantContext;
import com.openjiuwen.rdc.spi.registry.TenantIsolationViolationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link PgMvpDiscoveryServiceImpl} — Method A/B DTO field
 * fill strategy + {@code resolveRouteHandle} failure modes + tenant
 * isolation (ADR-0160 decisions 2 / 5 / 6, HD3-001/003/004/005/006, RB6).
 *
 * <p>Uses a hand-written fake {@link AgentRegistryRepository} and a real
 * {@link SimpleMeterRegistry} so {@link RegistryObservabilityConfig} is
 * exercised without mocking. Tenant context is the real
 * {@link com.openjiuwen.rdc.registry.runtime.tenant.ThreadLocalTenantContext}
 * so the optional cross-check (ADR-0160 decision 6) is covered too.
 *
 * <p>Authoritative behaviours:
 * <ul>
 *   <li><b>RB6-A</b> — Method A returns rich DTOs (business definition fields
 *       populated: agentName / agentType / systemProfile / toolSchemas).</li>
 *   <li><b>RB6-B</b> — Method B returns minimal DTOs (business definition
 *       fields null; ICD 5 routing fields only).</li>
 *   <li><b>RB7-tenant</b> — bound {@link TenantContext} mismatch raises
 *       {@link TenantIsolationViolationException}; unbound context skips
 *       the cross-check.</li>
 *   <li><b>RB-resolve</b> — malformed handle → IAE; entry_not_found →
 *       NoSuchElementException; tenant mismatch →
 *       TenantIsolationViolationException.</li>
 * </ul>
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

    // ---- RB6-A: Method A returns rich DTOs --------------------------------

    @Test
    void method_a_returns_rich_dto_with_business_definition_fields() {
        List<AgentCardDto> results = discovery.discoverBestAgents(
                "tenant-A", "自然语言 intent", null, 5);

        assertThat(results).hasSize(1);
        AgentCardDto dto = results.get(0);
        // ICD 5 routing fields always populated.
        assertThat(dto.getRouteHandle()).isNotBlank();
        assertThat(dto.getHealth()).isEqualTo("ONLINE");
        assertThat(dto.getContractVersion()).isEqualTo("1.0.0");
        assertThat(dto.getCapabilityVersion()).isEqualTo("2.1.0");
        assertThat(dto.getWeight()).isEqualTo(100);
        assertThat(dto.getRegion()).isEqualTo("cn-east-1");
        // Method A: business definition fields populated.
        assertThat(dto.getAgentName()).isEqualTo("财务助手");
        assertThat(dto.getAgentType()).isEqualTo("assistant");
        assertThat(dto.getSystemProfile()).isEqualTo("profile-a");
        assertThat(dto.getToolSchemas()).isEqualTo("[]");
    }

    // ---- RB6-B: Method B returns minimal DTOs -----------------------------

    @Test
    void method_b_returns_minimal_dto_with_business_definition_fields_null() {
        List<AgentCardDto> results = discovery.discoverBestAgents(
                "tenant-A", "cap-billing", "billing intent", null, 5);

        assertThat(results).hasSize(1);
        AgentCardDto dto = results.get(0);
        // ICD 5 routing fields always populated.
        assertThat(dto.getRouteHandle()).isNotBlank();
        assertThat(dto.getHealth()).isEqualTo("ONLINE");
        assertThat(dto.getContractVersion()).isEqualTo("1.0.0");
        assertThat(dto.getCapabilityVersion()).isEqualTo("2.1.0");
        assertThat(dto.getWeight()).isEqualTo(100);
        assertThat(dto.getRegion()).isEqualTo("cn-east-1");
        // Method B: business definition fields null.
        assertThat(dto.getAgentName()).isNull();
        assertThat(dto.getAgentType()).isNull();
        assertThat(dto.getSystemProfile()).isNull();
        assertThat(dto.getToolSchemas()).isNull();
    }

    @Test
    void method_a_and_b_produce_same_route_handle_for_same_row() {
        // Same row in tenant-A, agent-001 — handle encoding must be identical.
        AgentCardDto a = discovery.discoverBestAgents("tenant-A", "intent", null, 5).get(0);
        AgentCardDto b = discovery.discoverBestAgents(
                "tenant-A", "cap-billing", "intent", null, 5).get(0);
        assertThat(a.getRouteHandle()).isEqualTo(b.getRouteHandle());
    }

    // ---- RB7-tenant: TenantContext cross-check ----------------------------

    @Test
    void bound_tenant_context_mismatch_raises_isolation_violation() {
        tenantContext.set("tenant-other");
        assertThatThrownBy(() -> discovery.discoverBestAgents("tenant-A", "intent", null, 5))
                .isInstanceOf(TenantIsolationViolationException.class)
                .hasMessageContaining("tenant_isolation_violation");
    }

    @Test
    void bound_tenant_context_match_proceeds_normally() {
        tenantContext.set("tenant-A");
        List<AgentCardDto> results = discovery.discoverBestAgents(
                "tenant-A", "intent", null, 5);
        assertThat(results).hasSize(1);
    }

    @Test
    void unbound_tenant_context_skips_cross_check() {
        // Unbound (null) — explicit tenantId is the source of truth.
        assertThat(tenantContext.current()).isNull();
        List<AgentCardDto> results = discovery.discoverBestAgents(
                "tenant-A", "intent", null, 5);
        assertThat(results).hasSize(1);
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
        // Encoded tenant = tenant-A; caller passes tenant-A but binds tenant-C.
        // Encoded-vs-caller check passes (both tenant-A); the bound-context
        // cross-check (verifyTenant) catches the mismatch.
        tenantContext.set("tenant-C");
        String handle = RouteHandleCodec.encode(
                "tenant-A", "agent-001", "rk://svc/default", "1.0.0");

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-A"))
                .isInstanceOf(TenantIsolationViolationException.class);
    }

    // ---- Empty result scenarios ------------------------------------------

    @Test
    void method_a_returns_empty_list_when_repository_has_no_rows() {
        List<AgentCardDto> results = discovery.discoverBestAgents(
                "tenant-empty", "intent", null, 5);
        assertThat(results).isEmpty();
    }

    @Test
    void method_b_returns_empty_list_when_capability_not_found() {
        List<AgentCardDto> results = discovery.discoverBestAgents(
                "tenant-A", "cap-nonexistent", "intent", null, 5);
        assertThat(results).isEmpty();
    }

    // ---- Fakes -----------------------------------------------------------

    /**
     * Minimal fake {@link AgentRegistryRepository} for tenant-A / agent-001.
     * Other tenants / agents return empty results.
     */
    private static final class FakeRepository implements AgentRegistryRepository {
        @Override
        public void upsert(com.openjiuwen.rdc.spi.registry.AgentCard card) {
            // not exercised by these tests
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
        public List<RegistryRow> searchByIntent(String tenantId, String userQuery,
                                                String contractVersion, int topK) {
            if (!"tenant-A".equals(tenantId)) {
                return List.of();
            }
            return List.of(sampleRow());
        }

        @Override
        public List<RegistryRow> searchByCapability(String tenantId, String capability,
                                                    String userQuery, String contractVersion,
                                                    int topK) {
            if (!"tenant-A".equals(tenantId) || !"cap-billing".equals(capability)) {
                return List.of();
            }
            return List.of(sampleRow());
        }

        @Override
        public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId) {
            if ("tenant-A".equals(tenantId) && "agent-001".equals(agentId)) {
                return Optional.of(new EndpointEntry(
                        "https://agent.example/agent", "rk://svc/default", "1.0.0"));
            }
            return Optional.empty();
        }

        private static RegistryRow sampleRow() {
            return new RegistryRow(
                    "agent-001", "svc-001", "财务助手", "assistant",
                    "cap-billing", "rk://svc/default", "1.0.0", "2.1.0",
                    "profile-a", "[]", 100, "cn-east-1", "ONLINE");
        }
    }

    /**
     * Test-only {@link TenantContext} that exposes the bind / clear lifecycle
     * so tests can simulate bound vs unbound scopes without touching the real
     * ThreadLocal (which would leak across tests if not cleaned up).
     */
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
