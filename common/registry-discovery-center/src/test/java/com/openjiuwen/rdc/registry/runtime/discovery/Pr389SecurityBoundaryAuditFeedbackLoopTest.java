/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.spi.registry.TenantContext;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

/**
 * Feedback-loop tests for PR #389 review issue #1: security-boundary failure
 * paths (tenant_isolation_violation + malformed_handle) MUST be audited and
 * metricised just like the success / entry_not_found / generic-error paths.
 *
 * <p>Reviewer finding: {@code PgMvpDiscoveryServiceImpl.verifyTenant} and
 * {@code RouteHandleCodec.decode} both run <em>outside</em> the
 * {@code try { } finally { observeXxx(...) }} block, so:
 * <ul>
 *   <li>{@code tenant_isolation_violation} raises before the timer starts →
 *       no {@code agent_bus_registry_op_total{op=discover/resolve,
 *       outcome=tenant_isolation_violation}} increment, no audit line.</li>
 *   <li>{@code malformed_handle} (bad base64 / bad JSON) raises before the
 *       resolve timer starts → no
 *       {@code agent_bus_registry_op_total{op=resolve,
 *       outcome=malformed_handle}} increment, no audit line.</li>
 * </ul>
 *
 * <p>These tests fail RED against the unfixed code (no counter increment, no
 * audit outcome) and turn GREEN once verifyTenant / decode are pulled inside
 * the try block (or the safety exceptions get their own observe path).
 *
 * <p>Authority: PR #389 review issue #1 (audit / monitor on security failure
 * paths). ADR-0160 decisions 5 / 6 + HD3-005 / HD3-006. Revised for
 * FEAT-016 (RouteHandleCodec.encode takes instanceId; repo port returns
 * List via listByAgentId + listByServiceId + listByCapability; findEndpoint
 * takes instanceId; observeDiscover takes queryDimension/queryValue).
 *
 * @since 2026-07-10
 */
class Pr389SecurityBoundaryAuditFeedbackLoopTest {
    private static final String SERVICE_ID = "wealth-svc";
    private static final String INSTANCE_ID = "test-host-8080";

    private SimpleMeterRegistry meterRegistry;
    private RegistryObservabilityConfig observability;
    private TestTenantContext tenantContext;
    private PgMvpDiscoveryServiceImpl discovery;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        observability = new RegistryObservabilityConfig(meterRegistry);
        tenantContext = new TestTenantContext();
        discovery = new PgMvpDiscoveryServiceImpl(
                buildNoopRepository(),
                tenantContext,
                observability);
    }

    private AgentRegistryRepository buildNoopRepository() {
        return new AgentRegistryRepository() {
            @Override
            public void upsert(AgentRegistryEntry card, String a2aAgentCardJson) {
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
            public boolean updateStatus(AgentRegistryRepository.StatusUpdate update) {
                return false;
            }

            @Override
            public List<RegistryRow> listByAgentId(String tenantId, String agentId,
                                                   String contractVersion) {
                return List.of();
            }

            @Override
            public List<RegistryRow> listByServiceId(String tenantId, String serviceId,
                                                     String contractVersion) {
                return List.of();
            }

            @Override
            public List<RegistryRow> listByCapability(String tenantId, String capability,
                                                      String contractVersion) {
                return List.of();
            }

            @Override
            public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId,
                                                        String serviceId, String instanceId) {
                return Optional.empty();
            }
        };
    }

    @AfterEach
    void tearDown() {
        tenantContext.clear();
    }

    // ---- #1-A: tenant_isolation_violation must increment op_total ----------------

    @Test
    void tenant_isolation_violation_on_discover_increments_op_total() {
        tenantContext.set("tenant-bound");
        assertThatThrownBy(() -> discovery.searchInstancesByAgentId(
                "tenant-A", "agent-001", null))
                .isInstanceOf(TenantIsolationViolationException.class);

        assertThat(counter("discover", "tenant_isolation_violation"))
                .as("PR #389 #1: tenant_isolation_violation on discover MUST increment "
                    + "agent_bus_registry_op_total{op=discover, outcome=tenant_isolation_violation}")
                .isNotNull();
        assertThat(counter("discover", "tenant_isolation_violation").count())
                .as("tenant_isolation_violation counter must be incremented exactly once")
                .isEqualTo(1.0);
    }

    @Test
    void tenant_isolation_violation_on_resolve_increments_op_total() {
        // FEAT-016: v2: 6-field encode (adds instanceId).
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                "tenant-A", "agent-001", SERVICE_ID, INSTANCE_ID, "rk://svc/default", "1.0.0"));
        assertThatThrownBy(() -> discovery.resolveRouteHandle(handle, "tenant-B"))
                .isInstanceOf(TenantIsolationViolationException.class);

        assertThat(counter("resolve", "tenant_isolation_violation"))
                .as("PR #389 #1: tenant_isolation_violation on resolve MUST increment "
                    + "agent_bus_registry_op_total{op=resolve, outcome=tenant_isolation_violation}")
                .isNotNull();
        assertThat(counter("resolve", "tenant_isolation_violation").count())
                .isEqualTo(1.0);
    }

    // ---- #1-B: malformed_handle must increment op_total ------------------------

    @Test
    void malformed_handle_on_resolve_increments_op_total_counter() {
        assertThatThrownBy(() -> discovery.resolveRouteHandle(
                "!!!not-base64!!!", "tenant-A"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(counter("resolve", "malformed_handle"))
                .as("PR #389 #1: malformed_handle on resolve MUST increment "
                    + "agent_bus_registry_op_total{op=resolve, outcome=malformed_handle}")
                .isNotNull();
        assertThat(counter("resolve", "malformed_handle").count())
                .isEqualTo(1.0);
    }

    // ---- helpers -----------------------------------------------------------

    private io.micrometer.core.instrument.Counter counter(String op, String outcome) {
        return meterRegistry.find("agent_bus_registry_op_total")
                .tag("op", op)
                .tag("outcome", outcome)
                .counter();
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
