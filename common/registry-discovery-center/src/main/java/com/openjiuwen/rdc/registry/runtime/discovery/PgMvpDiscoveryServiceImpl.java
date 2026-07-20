/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime.discovery;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.registry.runtime.RegistryOpContext;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository.EndpointEntry;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository.RegistryRow;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.spi.registry.AgentDiscoveryService;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.spi.registry.TenantContext;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;

import org.slf4j.MDC;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MVP phase-1 implementation of {@link AgentDiscoveryService} — single
 * PostgreSQL lookup (ADR-0160 decisions 2 / 4 / 5 / 6, revised by
 * REQ-2026-004, REQ-2026-006, then FEAT-016).
 *
 * <p>Thin orchestrator: delegates persistence to {@link AgentRegistryRepository}
 * (port, ADR-0160 decision 4) and route-handle encoding to {@link RouteHandleCodec}
 * (internal to this package, ADR-0160 decision 5). No JDBC imports — the
 * {@code req-2026-003-jdbc-confined-to-persistence} gate enforces that.
 *
 * <p>FEAT-016: discovery now exposes three query dimensions —
 * {@link #searchInstancesByAgentId(String, String, String)},
 * {@link #searchByServiceId(String, String, String)}, and
 * {@link #searchByCapability(String, String, String)} — each accepting a
 * nullable {@code contractVersion} filter. DRAINING instances are now
 * included in results (was excluded in REQ-2026-006). The v2: 6-field route
 * handle carries {@code instanceId}; {@link #resolveRouteHandle} passes the
 * 4-field PK {@code (tenantId, agentId, serviceId, instanceId)} to
 * {@link AgentRegistryRepository#findEndpoint(String, String, String, String)}.
 *
 * <p>Tenant isolation (ADR-0160 decision 6, ESC-2 design pivot): HTTP-entry
 * callers pass {@code tenantId} explicitly — no {@code TenantFilter} populates
 * a {@code TenantContext} at Servlet entry. Background scheduling paths bind
 * via {@code ThreadLocalTenantContext.bindForScope}. The
 * {@link TenantContext#current()} cross-check is therefore optional: when a
 * tenant is bound, mismatch raises {@link TenantIsolationViolationException};
 * when unbound (HTTP entry), the check is skipped.
 *
 * <p>Failure modes for {@link #resolveRouteHandle}: malformed handle →
 * {@link IllegalArgumentException} (HTTP 400 {@code malformed_handle});
 * non-existent entry → {@link NoSuchElementException} (HTTP 404
 * {@code entry_not_found}); tenant mismatch →
 * {@link TenantIsolationViolationException} (HTTP 400
 * {@code tenant_isolation_violation}).
 *
 * @since 2026-07-10
 */
@Primary
@Service
public class PgMvpDiscoveryServiceImpl implements AgentDiscoveryService {
    private static final String DIM_AGENT_ID = "agentId";
    private static final String DIM_SERVICE_ID = "serviceId";
    private static final String DIM_CAPABILITY = "capability";

    private final AgentRegistryRepository repository;
    private final TenantContext tenantContext;
    private final RegistryObservabilityConfig observability;

    public PgMvpDiscoveryServiceImpl(AgentRegistryRepository repository,
                                     TenantContext tenantContext,
                                     RegistryObservabilityConfig observability) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.observability = Objects.requireNonNull(observability, "observability");
    }

    @Override
    public List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId, String contractVersion) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        int resultCount = 0;
        try {
            verifyTenant(tenantId);
            List<RegistryRow> rows = repository.listByAgentId(tenantId, agentId, contractVersion);
            resultCount = rows.size();
            if (rows.isEmpty()) {
                outcome = "not_found";
                return List.of();
            }
            outcome = "success";
            return rows.stream()
                    .map(row -> toDto(tenantId, row))
                    .toList();
        } catch (TenantIsolationViolationException ex) {
            outcome = "tenant_isolation_violation";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            RegistryOpContext ctx = RegistryOpContext.of(traceId, tenantId)
                    .query(DIM_AGENT_ID, agentId)
                    .build();
            observability.observeDiscover(ctx, outcome, resultCount, latencyMs);
            MDC.remove("traceId");
        }
    }

    @Override
    public List<AgentCardDto> searchByServiceId(String tenantId, String serviceId, String contractVersion) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        int resultCount = 0;
        try {
            verifyTenant(tenantId);
            List<RegistryRow> rows = repository.listByServiceId(tenantId, serviceId, contractVersion);
            resultCount = rows.size();
            if (rows.isEmpty()) {
                outcome = "not_found";
                return List.of();
            }
            outcome = "success";
            return rows.stream()
                    .map(row -> toDto(tenantId, row))
                    .toList();
        } catch (TenantIsolationViolationException ex) {
            outcome = "tenant_isolation_violation";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            RegistryOpContext ctx = RegistryOpContext.of(traceId, tenantId)
                    .query(DIM_SERVICE_ID, serviceId)
                    .build();
            observability.observeDiscover(ctx, outcome, resultCount, latencyMs);
            MDC.remove("traceId");
        }
    }

    @Override
    public List<AgentCardDto> searchByCapability(String tenantId, String capability, String contractVersion) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        int resultCount = 0;
        try {
            verifyTenant(tenantId);
            List<RegistryRow> rows = repository.listByCapability(tenantId, capability, contractVersion);
            resultCount = rows.size();
            if (rows.isEmpty()) {
                outcome = "not_found";
                return List.of();
            }
            outcome = "success";
            return rows.stream()
                    .map(row -> toDto(tenantId, row))
                    .toList();
        } catch (TenantIsolationViolationException ex) {
            outcome = "tenant_isolation_violation";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            RegistryOpContext ctx = RegistryOpContext.of(traceId, tenantId)
                    .query(DIM_CAPABILITY, capability)
                    .build();
            observability.observeDiscover(ctx, outcome, resultCount, latencyMs);
            MDC.remove("traceId");
        }
    }

    @Override
    public RouteResolution resolveRouteHandle(String routeHandle, String tenantId) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            RouteHandleCodec.HandleFields decoded;
            try {
                decoded = RouteHandleCodec.decode(routeHandle);
            } catch (IllegalArgumentException ex) {
                outcome = "malformed_handle";
                throw ex;
            }
            if (!decoded.tenantId().equals(tenantId)) {
                outcome = "tenant_isolation_violation";
                throw new TenantIsolationViolationException(decoded.tenantId(), tenantId);
            }
            verifyTenant(tenantId);
            // FEAT-016: pass the 4-field PK (tenantId, agentId, serviceId,
            // instanceId) decoded from the v2: 6-field handle.
            Optional<EndpointEntry> endpoint = repository.findEndpoint(
                    tenantId, decoded.agentId(), decoded.serviceId(), decoded.instanceId());
            if (endpoint.isEmpty()) {
                outcome = "entry_not_found";
                throw new NoSuchElementException("entry_not_found: tenant=" + tenantId
                        + ", agentId=" + decoded.agentId()
                        + ", serviceId=" + decoded.serviceId()
                        + ", instanceId=" + decoded.instanceId());
            }
            EndpointEntry ep = endpoint.get();
            outcome = "success";
            return new RouteResolution(decoded.instanceId(), ep.endpointUrl(),
                    ep.routeKey(), ep.contractVersion());
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeResolve(
                    RegistryOpContext.of(traceId, tenantId).routeHandleId(routeHandle).build(),
                    outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * Optional tenant cross-check (ADR-0160 decision 6). When a tenant is
     * bound to the current thread (background scheduling via
     * {@code bindForScope}), mismatch raises {@link TenantIsolationViolationException}.
     * When unbound (HTTP entry passes {@code tenantId} explicitly), the check
     * is skipped — the explicit parameter is the source of truth.
     *
     * @param tenantId the tenant ID passed explicitly by the caller (HTTP entry) —
     *                 the source of truth against which any thread-bound tenant is checked
     * @throws TenantIsolationViolationException if a tenant is bound to the current
     *                                          thread and does not match {@code tenantId}
     */
    private void verifyTenant(String tenantId) {
        String bound = tenantContext.current();
        if (bound != null && !bound.equals(tenantId)) {
            throw new TenantIsolationViolationException(tenantId, bound);
        }
    }

    /**
     * Rich DTO — all routing fields + business definition fields populated.
     * FEAT-016: encode includes {@code instanceId} (v2: 6-field); DTO
     * includes {@code serviceId} (logical service identifier, visible in the
     * agent/client projection layer per L2 §2.3.2).
     *
     * @param tenantId the tenant ID owning the row (encoded into the route handle)
     * @param row      the persistence row to project
     * @return a fully-populated {@link AgentCardDto} with a fresh route handle
     */
    private AgentCardDto toDto(String tenantId, RegistryRow row) {
        String handle = RouteHandleCodec.encode(new RouteHandleCodec.HandleFields(
                tenantId, row.agentId(), row.serviceId(), row.instanceId(),
                row.routeKey(), row.contractVersion()));
        return AgentCardDto.builder()
                .serviceId(row.serviceId())
                .routeHandle(handle)
                .health(row.status())
                .contractVersion(row.contractVersion())
                .capabilityVersion(row.capabilityVersion())
                .weight(row.weight())
                .region(row.region())
                .maxConcurrency(row.maxConcurrency())
                .agentName(row.agentName())
                .frameworkType(row.frameworkType())
                .build();
    }
}
