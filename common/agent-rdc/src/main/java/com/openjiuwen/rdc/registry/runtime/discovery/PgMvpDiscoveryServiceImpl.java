package com.openjiuwen.rdc.registry.runtime.discovery;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository.EndpointEntry;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository.RegistryRow;
import com.openjiuwen.rdc.spi.registry.AgentCardDto;
import com.openjiuwen.rdc.spi.registry.AgentDiscoveryService;
import com.openjiuwen.rdc.spi.registry.RouteResolution;
import com.openjiuwen.rdc.spi.registry.TenantContext;
import com.openjiuwen.rdc.spi.registry.TenantIsolationViolationException;
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
 * PostgreSQL + tsvector (ADR-0160 decisions 2 / 4 / 5 / 6).
 *
 * <p>Thin orchestrator: delegates persistence to {@link AgentRegistryRepository}
 * (port, ADR-0160 decision 4) and route-handle encoding to {@link RouteHandleCodec}
 * (internal to this package, ADR-0160 decision 5). No JDBC imports — the
 * {@code req-2026-003-jdbc-confined-to-persistence} gate enforces that.
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
 * <p>Authority: ADR-0160 decisions 2 / 4 / 5 / 6 + HD3-001 / 003 / 004 / 005 / 006.
 */
@Primary
@Service
public class PgMvpDiscoveryServiceImpl implements AgentDiscoveryService {

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
    public List<AgentCardDto> discoverBestAgents(String tenantId,
                                                 String userQuery,
                                                 String contractVersion,
                                                 int topK) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "success";
        try {
            verifyTenant(tenantId);
            List<RegistryRow> rows = repository.searchByIntent(tenantId, userQuery, contractVersion, topK);
            List<AgentCardDto> dtos = rows.stream()
                    .map(row -> toRichDto(tenantId, row))
                    .toList();
            return dtos;
        } catch (TenantIsolationViolationException ex) {
            outcome = "tenant_isolation_violation";
            throw ex;
        } catch (RuntimeException ex) {
            outcome = "error";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDiscover(traceId, tenantId, null, contractVersion,
                    outcome, -1, latencyMs);
            MDC.remove("traceId");
        }
    }

    @Override
    public List<AgentCardDto> discoverBestAgents(String tenantId,
                                                 String capability,
                                                 String userQuery,
                                                 String contractVersion,
                                                 int topK) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "success";
        int resultCount = -1;
        try {
            verifyTenant(tenantId);
            List<RegistryRow> rows = repository.searchByCapability(
                    tenantId, capability, userQuery, contractVersion, topK);
            resultCount = rows.size();
            return rows.stream()
                    .map(row -> toMinimalDto(tenantId, row))
                    .toList();
        } catch (TenantIsolationViolationException ex) {
            outcome = "tenant_isolation_violation";
            throw ex;
        } catch (RuntimeException ex) {
            outcome = "error";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDiscover(traceId, tenantId, capability, contractVersion,
                    outcome, resultCount, latencyMs);
            MDC.remove("traceId");
        }
    }

    @Override
    public RouteResolution resolveRouteHandle(String routeHandle, String tenantId) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "success";
        try {
            RouteHandleCodec.DecodedHandle decoded;
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
            Optional<EndpointEntry> endpoint = repository.findEndpoint(tenantId, decoded.agentId());
            if (endpoint.isEmpty()) {
                outcome = "entry_not_found";
                throw new NoSuchElementException("entry_not_found: tenant=" + tenantId
                        + ", agentId=" + decoded.agentId());
            }
            EndpointEntry ep = endpoint.get();
            return new RouteResolution(ep.endpointUrl(), ep.routeKey(), ep.contractVersion());
        } catch (RuntimeException ex) {
            if ("entry_not_found".equals(outcome)
                    || "malformed_handle".equals(outcome)
                    || "tenant_isolation_violation".equals(outcome)) {
                // already set; keep outcome
            } else {
                outcome = "error";
            }
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeResolve(traceId, tenantId, routeHandle, outcome, latencyMs);
            MDC.remove("traceId");
        }
    }

    /**
     * Optional tenant cross-check (ADR-0160 decision 6). When a tenant is
     * bound to the current thread (background scheduling via
     * {@code bindForScope}), mismatch raises {@link TenantIsolationViolationException}.
     * When unbound (HTTP entry passes {@code tenantId} explicitly), the check
     * is skipped — the explicit parameter is the source of truth.
     */
    private void verifyTenant(String tenantId) {
        String bound = tenantContext.current();
        if (bound != null && !bound.equals(tenantId)) {
            throw new TenantIsolationViolationException(tenantId, bound);
        }
    }

    /**
     * Method A — rich DTO (business definition fields populated, ADR-0160 decision 2).
     */
    private AgentCardDto toRichDto(String tenantId, RegistryRow row) {
        String handle = RouteHandleCodec.encode(
                tenantId, row.agentId(), row.routeKey(), row.contractVersion());
        return AgentCardDto.builder()
                .routeHandle(handle)
                .health(row.status())
                .contractVersion(row.contractVersion())
                .capabilityVersion(row.capabilityVersion())
                .weight(row.weight())
                .region(row.region())
                .agentName(row.agentName())
                .agentType(row.agentType())
                .systemProfile(row.systemProfile())
                .toolSchemas(row.toolSchemas())
                .build();
    }

    /**
     * Method B — minimal DTO (business definition fields null, ADR-0160 decision 2).
     */
    private AgentCardDto toMinimalDto(String tenantId, RegistryRow row) {
        String handle = RouteHandleCodec.encode(
                tenantId, row.agentId(), row.routeKey(), row.contractVersion());
        return AgentCardDto.builder()
                .routeHandle(handle)
                .health(row.status())
                .contractVersion(row.contractVersion())
                .capabilityVersion(row.capabilityVersion())
                .weight(row.weight())
                .region(row.region())
                .build();
    }
}
