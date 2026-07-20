/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.FrameworkType;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.tenant.TenantContext;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Unit tests for {@link PgMvpDiscoveryServiceImpl} —
 * {@code searchInstancesByAgentId} / {@code searchByServiceId} /
 * {@code searchByCapability} DTO field fill strategy +
 * {@code resolveRouteHandle} failure modes + tenant isolation (ADR-0160
 * decisions 2 / 5 / 6, HD3-001/003/004/005/006).
 *
 * <p>FEAT-016: discovery now exposes three query dimensions (agentId /
 * serviceId / capability), each accepting a nullable contractVersion filter.
 * DRAINING is now included in results (was excluded in REQ-2026-006). The
 * v2: 6-field route handle carries instanceId; resolveRouteHandle passes the
 * 4-field PK to findEndpoint.
 */
class PgMvpDiscoveryServiceImplTest {
    private static final String TENANT = "tenant-A";
    private static final String AGENT_001 = "agent-001";
    private static final String AGENT_002 = "agent-002";
    private static final String SERVICE_ID = "wealth-svc";
    private static final String INSTANCE_ID = "test-host-8080";
    private static final String ROUTE_KEY = "rk://svc/default";
    private static final String CONTRACT = "1.0.0";
    private static final String CAPABILITY = "wealth.purchase";

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

    // ---- searchInstancesByAgentId: rich DTO populated ---------------------

    @Test
    void search_by_agent_id_returns_rich_dto_all_fields_populated() {
        List<AgentCardDto> result = discovery.searchInstancesByAgentId(TENANT, AGENT_001, null);

        assertThat(result).hasSize(1);
        AgentCardDto dto = result.get(0);
        assertThat(dto.getServiceId()).isEqualTo(SERVICE_ID);
        assertThat(dto.getRouteHandle()).isNotBlank();
        assertThat(dto.getHealth()).isEqualTo("ONLINE");
        assertThat(dto.getContractVersion()).isEqualTo(CONTRACT);
        assertThat(dto.getCapabilityVersion()).isEqualTo("2.1.0");
        assertThat(dto.getWeight()).isEqualTo(100);
        assertThat(dto.getRegion()).isEqualTo("cn-east-1");
        assertThat(dto.getMaxConcurrency()).isEqualTo(10);
        assertThat(dto.getAgentName()).isEqualTo("财务助手");
        assertThat(dto.getFrameworkType()).isEqualTo(FrameworkType.JIUWEN);
    }

    @Test
    void dto_contains_service_id() {
        // FEAT-016: AgentCardDto exposes serviceId (logical service identifier)
        // in the agent/client projection layer per L2 §2.3.2.
        List<AgentCardDto> result = discovery.searchInstancesByAgentId(TENANT, AGENT_001, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getServiceId()).isEqualTo(SERVICE_ID);
    }

    @Test
    void search_instances_by_agent_id_returns_empty_list_when_not_found() {
        List<AgentCardDto> result = discovery.searchInstancesByAgentId(TENANT, "agent-999", null);
        assertThat(result).isEmpty();
    }

    @Test
    void search_by_agent_id_returns_empty_for_unknown_tenant() {
        List<AgentCardDto> result = discovery.searchInstancesByAgentId("tenant-unknown", AGENT_001, null);
        assertThat(result).isEmpty();
    }

    @Test
    void search_by_agent_id_returns_draining_as_limited_available() {
        // FEAT-016: DRAINING is now included in discovery results (was
        // excluded in REQ-2026-006). The caller sees DRAINING as a
        // limited-availability health state.
        List<AgentCardDto> result = discovery.searchInstancesByAgentId(TENANT, AGENT_002, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getHealth()).isEqualTo("DRAINING");
    }

    // ---- searchByServiceId / searchByCapability (FEAT-016 new) -----------

    @Test
    void search_by_service_id_returns_matching_instances() {
        List<AgentCardDto> result = discovery.searchByServiceId(TENANT, SERVICE_ID, null);
        assertThat(result).hasSize(1);
        AgentCardDto dto = result.get(0);
        assertThat(dto.getServiceId()).isEqualTo(SERVICE_ID);
        assertThat(dto.getAgentName()).isEqualTo("财务助手");
    }

    @Test
    void search_by_capability_returns_matching_instances() {
        List<AgentCardDto> result = discovery.searchByCapability(TENANT, CAPABILITY, null);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getAgentName()).isEqualTo("财务助手");
    }

    @Test
    void contract_version_filter_returns_empty_when_no_match() {
        // FEAT-016: nullable contractVersion filter — non-null with no
        // matching rows returns empty list.
        List<AgentCardDto> result = discovery.searchInstancesByAgentId(TENANT, AGENT_001, "9.9.9");
        assertThat(result).isEmpty();
    }

    // ---- RB7-tenant: TenantContext cross-check ----------------------------

    @Test
    void bound_tenant_context_mismatch_raises_isolation_violation() {
        tenantContext.set("tenant-other");
        assertThatThrownBy(() -> discovery.searchInstancesByAgentId(TENANT, AGENT_001, null))
                .isInstanceOf(TenantIsolationViolationException.class)
                .hasMessageContaining("tenant_isolation_violation");
    }

    @Test
    void bound_tenant_context_match_proceeds_normally() {
        tenantContext.set(TENANT);
        List<AgentCardDto> result = discovery.searchInstancesByAgentId(TENANT, AGENT_001, null);
        assertThat(result).hasSize(1);
    }

    @Test
    void unbound_tenant_context_skips_cross_check() {
        assertThat(tenantContext.current()).isNull();
        List<AgentCardDto> result = discovery.searchInstancesByAgentId(TENANT, AGENT_001, null);
        assertThat(result).hasSize(1);
    }

    // ---- RB-resolve: resolveRouteHandle failure modes ---------------------

    @Test
    void resolve_route_handle_returns_endpoint_for_existing_entry() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT_001, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT));

        RouteResolution resolution = discovery.resolveRouteHandle(handle, TENANT);

        assertThat(resolution.instanceId()).isEqualTo(INSTANCE_ID);
        assertThat(resolution.endpointUrl()).isEqualTo("https://agent.example/agent");
        assertThat(resolution.routeKey()).isEqualTo(ROUTE_KEY);
        assertThat(resolution.contractVersion()).isEqualTo(CONTRACT);
    }

    @Test
    void resolve_route_handle_tenant_mismatch_raises_isolation_violation() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT_001, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT));

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-B"))
                .isInstanceOf(TenantIsolationViolationException.class)
                .hasMessageContaining("tenant_isolation_violation");
    }

    @Test
    void resolve_route_handle_malformed_raises_illegal_argument() {
        assertThatThrownBy(() -> discovery.resolveRouteHandle("!!!not-base64!!!", TENANT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolve_route_handle_nonexistent_entry_raises_no_such_element() {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, "agent-999", SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT));

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, TENANT))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("entry_not_found");
    }

    @Test
    void resolve_route_handle_bound_mismatched_tenant_isolation_violation() {
        tenantContext.set("tenant-C");
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                TENANT, AGENT_001, SERVICE_ID, INSTANCE_ID, ROUTE_KEY, CONTRACT));

        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, TENANT))
                .isInstanceOf(TenantIsolationViolationException.class);
    }

    // ---- Fakes -----------------------------------------------------------

    private static final class FakeRepository implements AgentRegistryRepository {
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
        public boolean delete(String tenantId, String agentId, String serviceId, String instanceId) {
            return false;
        }

        @Override
        public java.util.List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
            return java.util.List.of();
        }

        @Override
        public boolean updateStatus(AgentRegistryRepository.StatusUpdate update) {
            return false;
        }

        @Override
        public List<RegistryRow> listByAgentId(String tenantId, String agentId, String contractVersion) {
            if (!"tenant-A".equals(tenantId)) {
                return List.of();
            }
            if ("agent-001".equals(agentId)) {
                if (contractVersion != null && !"1.0.0".equals(contractVersion)) {
                    return List.of();
                }
                return List.of(sampleRow(AGENT_001, "财务助手", "ONLINE",
                        java.util.List.of(CAPABILITY)));
            }
            if ("agent-002".equals(agentId)) {
                // FEAT-016: DRAINING now returned (was filtered in REQ-2026-006).
                if (contractVersion != null && !"1.0.0".equals(contractVersion)) {
                    return List.of();
                }
                return List.of(sampleRow(AGENT_002, "draining-agent",
                        "DRAINING", java.util.List.of()));
            }
            return List.of();
        }

        @Override
        public List<RegistryRow> listByServiceId(String tenantId, String serviceId, String contractVersion) {
            if (!"tenant-A".equals(tenantId) || !SERVICE_ID.equals(serviceId)) {
                return List.of();
            }
            if (contractVersion != null && !"1.0.0".equals(contractVersion)) {
                return List.of();
            }
            return List.of(sampleRow(AGENT_001, "财务助手", "ONLINE",
                    java.util.List.of(CAPABILITY)));
        }

        @Override
        public List<RegistryRow> listByCapability(String tenantId, String capability, String contractVersion) {
            if (!"tenant-A".equals(tenantId) || !CAPABILITY.equals(capability)) {
                return List.of();
            }
            if (contractVersion != null && !"1.0.0".equals(contractVersion)) {
                return List.of();
            }
            return List.of(sampleRow(AGENT_001, "财务助手", "ONLINE",
                    java.util.List.of(CAPABILITY)));
        }

        @Override
        public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId,
                                                    String serviceId, String instanceId) {
            if ("tenant-A".equals(tenantId) && "agent-001".equals(agentId)
                    && SERVICE_ID.equals(serviceId) && INSTANCE_ID.equals(instanceId)) {
                return Optional.of(new EndpointEntry(
                        "https://agent.example/agent", ROUTE_KEY, CONTRACT));
            }
            return Optional.empty();
        }

        private static RegistryRow sampleRow(String agentId, String agentName, String status,
                                             java.util.List<String> capabilities) {
            return new RegistryRow(
                    SERVICE_ID, INSTANCE_ID, agentId, agentName, FrameworkType.JIUWEN,
                    ROUTE_KEY, CONTRACT, "2.1.0",
                    100, "cn-east-1", 10, status, capabilities);
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
