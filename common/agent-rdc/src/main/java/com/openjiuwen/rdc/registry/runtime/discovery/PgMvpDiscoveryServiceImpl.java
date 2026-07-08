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

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MVP phase-1 implementation of {@link AgentDiscoveryService} — single
 * PostgreSQL lookup (ADR-0160 decisions 2 / 4 / 5 / 6, revised by
 * REQ-2026-004).
 *
 * <p>Thin orchestrator: delegates persistence to {@link AgentRegistryRepository}
 * (port, ADR-0160 decision 4) and route-handle encoding to {@link RouteHandleCodec}
 * (internal to this package, ADR-0160 decision 5). No JDBC imports — the
 * {@code req-2026-003-jdbc-confined-to-persistence} gate enforces that.
 *
 * <p>REQ-2026-004: the dual {@code discoverBestAgents} overloads (Method A
 * free-text + Method B capability-scoped) are removed; discovery collapses
 * to {@link #searchByAgentId(String, String)} single-value point lookup.
 * Free-text search infrastructure (tsvector + GIN index) is dropped in V4.
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
    public Optional<AgentCardDto> searchByAgentId(String tenantId, String agentId) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "success";
        int resultCount = 0;
        try {
            verifyTenant(tenantId);
            Optional<RegistryRow> row = repository.searchByAgentId(tenantId, agentId);
            if (row.isEmpty()) {
                outcome = "not_found";
                return Optional.empty();
            }
            resultCount = 1;
            return Optional.of(toDto(tenantId, row.get()));
        } catch (TenantIsolationViolationException ex) {
            outcome = "tenant_isolation_violation";
            throw ex;
        } catch (RuntimeException ex) {
            outcome = "error";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDiscover(traceId, tenantId, agentId,
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
     * Rich DTO — all ICD 5 routing fields + business definition fields
     * populated (single-value lookup has no "minimal DTO" variant).
     */
    private AgentCardDto toDto(String tenantId, RegistryRow row) {
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
                .frameworkType(row.frameworkType())
                .build();
    }
}
