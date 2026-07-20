/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import com.openjiuwen.rdc.config.RegistryObservabilityConfig;
import com.openjiuwen.rdc.config.RegistryOpAudit;
import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.DeadlineExceededException;
import com.openjiuwen.rdc.model.DiscoveryQuery;
import com.openjiuwen.rdc.model.DiscoveryResult;
import com.openjiuwen.rdc.model.EntryNotFoundException;
import com.openjiuwen.rdc.model.LeaseExpiredException;
import com.openjiuwen.rdc.model.LifecycleStatus;
import com.openjiuwen.rdc.model.MalformedRouteHandleException;
import com.openjiuwen.rdc.model.RegistryRequestDeadline;
import com.openjiuwen.rdc.model.RegistryUnavailableException;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.EndpointEntry;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.RegistryRow;
import com.openjiuwen.rdc.repository.AgentRegistryRepository.ResolveRow;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.RegistryPersistenceGuard;
import com.openjiuwen.rdc.security.CallerAuthorizationPolicy;
import com.openjiuwen.rdc.tenant.TenantContext;

import org.slf4j.MDC;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MVP phase-1 implementation of {@link AgentDiscoveryService} — single
 * PostgreSQL lookup (ADR-0160 decisions 2 / 4 / 5 / 6, revised by
 * REQ-2026-004, then REQ-2026-006).
 *
 * <p>Thin orchestrator: delegates persistence to {@link AgentRegistryRepository}
 * (port, ADR-0160 decision 4) and route-handle encoding to {@link RouteHandleCodec}
 * (internal to this package, ADR-0160 decision 5). No JDBC imports — the
 * {@code req-2026-003-jdbc-confined-to-persistence} gate enforces that.
 *
 * <p>REQ-2026-006: discovery evolves from single-value point lookup to
 * <em>list</em> lookup — {@link #searchInstancesByAgentId(String, String)}
 * returns all ONLINE/DEGRADED instances for a given {@code agentId}, each
 * with its own opaque {@code routeHandle} (encoding {@code serviceId}). The
 * caller selects an instance and resolves it via
 * {@link #resolveRouteHandle(String, String)}, which now passes the triple
 * {@code (tenantId, agentId, serviceId)} to
 * {@link AgentRegistryRepository#findEndpoint(String, String, String)}.
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
 * @since 0.1.0
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
    private final CallerAuthorizationPolicy callerAuthorizationPolicy;

    public PgMvpDiscoveryServiceImpl(AgentRegistryRepository repository,
                                     TenantContext tenantContext,
                                     RegistryObservabilityConfig observability,
                                     CallerAuthorizationPolicy callerAuthorizationPolicy) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.tenantContext = Objects.requireNonNull(tenantContext, "tenantContext");
        this.observability = Objects.requireNonNull(observability, "observability");
        this.callerAuthorizationPolicy = callerAuthorizationPolicy != null
                ? callerAuthorizationPolicy
                : new CallerAuthorizationPolicy.Permissive();
    }

    /**
     * searchInstancesByAgentId.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param contractVersion contractVersion
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId, String contractVersion) {
        return searchRuntime(DIM_AGENT_ID, agentId, tenantId, contractVersion,
                () -> repository.listByAgentId(tenantId, agentId, contractVersion));
    }

    /**
     * searchByServiceId.
     *
     * @param tenantId tenantId
     * @param serviceId serviceId
     * @param contractVersion contractVersion
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<AgentCardDto> searchByServiceId(String tenantId, String serviceId, String contractVersion) {
        return searchRuntime(DIM_SERVICE_ID, serviceId, tenantId, contractVersion,
                () -> repository.listByServiceId(tenantId, serviceId, contractVersion));
    }

    /**
     * searchByCapability.
     *
     * @param tenantId tenantId
     * @param capability capability
     * @param contractVersion contractVersion
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<AgentCardDto> searchByCapability(String tenantId, String capability, String contractVersion) {
        return searchRuntime(DIM_CAPABILITY, capability, tenantId, contractVersion,
                () -> repository.listByCapability(tenantId, capability, contractVersion));
    }

    private List<AgentCardDto> searchRuntime(
            String dimension, String value, String tenantId, String contractVersion,
            java.util.function.Supplier<List<RegistryRow>> query) {
        String traceId = UUID.randomUUID().toString();
        MDC.put("traceId", traceId);
        long start = System.nanoTime();
        String outcome = "error";
        int resultCount = 0;
        try {
            verifyTenant(tenantId, traceId);
            List<RegistryRow> rows = RegistryPersistenceGuard.execute(traceId, query);
            resultCount = rows.size();
            if (rows.isEmpty()) {
                outcome = "not_found";
                return List.of();
            }
            outcome = "success";
            return rows.stream().map(row -> toDto(tenantId, row)).toList();
        } catch (TenantIsolationViolationException ex) {
                outcome = "tenant_isolation_violation";
                throw ex;
                } finally {
                long latencyMs = (System.nanoTime() - start) / 1_000_000;
                observability.observeDiscover(
                new RegistryOpAudit(traceId, tenantId, value, null, null, null, null, outcome, latencyMs),
                resultCount);
                MDC.remove("traceId");
            }
    }

    /**
     * discover.
     *
     * @param query query
     * @return result
     * @since 0.1.0
     */
    @Override
    public DiscoveryResult discover(DiscoveryQuery query) {
        long start = System.nanoTime();
        String outcome = "error";
        String traceId = query.context().traceId();
        try {
            query.context().validate();
            RegistryRequestDeadline.enforce(query.context().deadline(), traceId);
            verifyTenant(query.context().tenantId(), traceId);
            callerAuthorizationPolicy.authorize(
                    query.context().tenantId(),
                    query.context().callerRef(),
                    traceId);
            DiscoveryResult result = RegistryPersistenceGuard.execute(
                    traceId, () -> StructuredDiscoveryEngine.discover(repository, query));
            outcome = result.outcome().name();
            return result;
        } catch (TenantIsolationViolationException ex) {
            outcome = "tenant_isolation_violation";
            throw ex;
        } catch (MalformedRouteHandleException ex) {
            outcome = "malformed_handle";
            throw ex;
        } catch (EntryNotFoundException ex) {
            outcome = "entry_not_found";
            throw ex;
        } catch (LeaseExpiredException ex) {
            outcome = "lease_expired";
            throw ex;
        } catch (DeadlineExceededException ex) {
            outcome = "deadline_exceeded";
            throw ex;
        } catch (RegistryUnavailableException ex) {
            outcome = "registry_unavailable";
            throw ex;
        } finally {
            long latencyMs = (System.nanoTime() - start) / 1_000_000;
            observability.observeDiscover(
                    new RegistryOpAudit(
                            traceId,
                            query.context().tenantId(),
                            query.agentId() != null ? query.agentId() : query.serviceId(),
                            null, null, null, null, outcome, latencyMs),
                    0);
        }
    }

    /**
     * resolveRouteHandle.
     *
     * @param routeHandle routeHandle
     * @param tenantId tenantId
     * @return result
     * @since 0.1.0
     */
    @Override
    public RouteResolution resolveRouteHandle(String routeHandle, String tenantId) {
        return resolveRouteHandle(routeHandle, tenantId, null, null);
    }

    /**
     * * Resolve with optional caller governance context (0711 {@code ResolveRouteHandle}).
     *
     * @param routeHandle routeHandle
     * @param tenantId tenantId
     * @param callerRef callerRef
     * @param traceId traceId
     * @return result
     * @since 0.1.0
     */
    public RouteResolution resolveRouteHandle(String routeHandle, String tenantId,
                                              String callerRef, String traceId) {
        return resolveRouteHandle(routeHandle, tenantId, callerRef, traceId, null);
    }

    /**
     * * Resolve with caller governance context and optional deadline (0711).
     *
     * @param routeHandle routeHandle
     * @param tenantId tenantId
     * @param callerRef callerRef
     * @param traceId traceId
     * @param deadline deadline
     * @return result
     * @since 0.1.0
     */
    public RouteResolution resolveRouteHandle(String routeHandle, String tenantId,
                                              String callerRef, String traceId,
                                              Instant deadline) {
        String effectiveTraceId = traceId != null && !traceId.isBlank() ? traceId : UUID.randomUUID().toString();
        MDC.put("traceId", effectiveTraceId);
        long start = System.nanoTime();
        String outcome = "error";
        try {
            if (deadline != null) {
                RegistryRequestDeadline.enforce(deadline, effectiveTraceId);
            }
            if (callerRef != null && !callerRef.isBlank()) {
                callerAuthorizationPolicy.authorize(tenantId, callerRef, effectiveTraceId);
            }
            RouteHandleCodec.HandleFields decoded = decodeRouteHandle(routeHandle, tenantId, effectiveTraceId);
            verifyTenant(tenantId, effectiveTraceId);
            RouteResolution resolution = lookupRouteResolution(tenantId, decoded, effectiveTraceId);
            outcome = "success";
            return resolution;
        } catch (TenantIsolationViolationException ex) {
                if ("error".equals(outcome)) {
                outcome = "tenant_isolation_violation";
            }
            throw ex;
        } catch (MalformedRouteHandleException ex) {
                if ("error".equals(outcome)) {
                    outcome = "malformed_handle";
                }
            throw ex;
        } catch (EntryNotFoundException ex) {
                    if ("error".equals(outcome)) {
                    outcome = "entry_not_found";
                }
            throw ex;
        } catch (LeaseExpiredException ex) {
                    if ("error".equals(outcome)) {
                    outcome = "lease_expired";
                }
            throw ex;
        } catch (DeadlineExceededException ex) {
                    outcome = "deadline_exceeded";
                    throw ex;
                    } catch (RegistryUnavailableException ex) {
                    outcome = "registry_unavailable";
                    throw ex;
                    } finally {
                    long latencyMs = (System.nanoTime() - start) / 1_000_000;
                    observability.observeResolve(new RegistryOpAudit(
                    effectiveTraceId, tenantId, null, null, null, null, routeHandle, outcome, latencyMs));
                    MDC.remove("traceId");
                }
    }

    private RouteHandleCodec.HandleFields decodeRouteHandle(
            String routeHandle, String tenantId, String effectiveTraceId) {
        try {
            RouteHandleCodec.HandleFields decoded = RouteHandleCodec.decode(routeHandle);
            if (!decoded.tenantId().equals(tenantId)) {
                throw new TenantIsolationViolationException(decoded.tenantId(), tenantId, effectiveTraceId);
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
                throw new MalformedRouteHandleException(ex.getMessage(), effectiveTraceId);
            }
    }

    private RouteResolution lookupRouteResolution(
            String tenantId, RouteHandleCodec.HandleFields decoded, String effectiveTraceId) {
        Optional<ResolveRow> resolved = RegistryPersistenceGuard.execute(
                effectiveTraceId,
                () -> repository.findForResolve(
                        tenantId, decoded.agentId(), decoded.serviceId(), decoded.instanceId()));
        if (resolved.isEmpty()) {
            return lookupLegacyEndpoint(tenantId, decoded, effectiveTraceId);
        }
        ResolveRow row = resolved.get();
        if (!LifecycleStatus.ACTIVE.name().equals(row.lifecycleStatus())) {
            throw new EntryNotFoundException(
                    "entry not found: lifecycle=" + row.lifecycleStatus(),
                    effectiveTraceId);
        }
        Instant now = Instant.now();
        if (row.leaseExpiresAt() != null && !row.leaseExpiresAt().isAfter(now)) {
            throw new LeaseExpiredException(effectiveTraceId);
        }
        return new RouteResolution(
                decoded.instanceId(), row.endpointUrl(), row.routeKey(),
                row.contractVersion(), row.capabilityVersion());
    }

    private RouteResolution lookupLegacyEndpoint(
            String tenantId, RouteHandleCodec.HandleFields decoded, String effectiveTraceId) {
            Optional<EndpointEntry> endpoint = RegistryPersistenceGuard.execute(
            effectiveTraceId,
            () -> repository.findEndpoint(
            tenantId, decoded.agentId(), decoded.serviceId(), decoded.instanceId()));
            if (endpoint.isEmpty()) {
            throw new EntryNotFoundException(
            "entry not found: tenant=" + tenantId
            + ", agentId=" + decoded.agentId()
            + ", serviceId=" + decoded.serviceId()
            + ", instanceId=" + decoded.instanceId(),
            effectiveTraceId);
        }
        EndpointEntry ep = endpoint.get();
        return new RouteResolution(
                decoded.instanceId(), ep.endpointUrl(), ep.routeKey(), ep.contractVersion(), null);
    }

    /**
     * Optional tenant cross-check (ADR-0160 decision 6). When a tenant is
     * bound to the current thread (background scheduling via
     * {@code bindForScope}), mismatch raises {@link TenantIsolationViolationException}.
     * When unbound (HTTP entry passes {@code tenantId} explicitly), the check
     * is skipped — the explicit parameter is the source of truth.
     *
     * @param tenantId tenantId
     * @param traceId traceId
     * @since 0.1.0
     */
    private void verifyTenant(String tenantId, String traceId) {
        String bound = tenantContext.current();
        if (bound != null && !bound.equals(tenantId)) {
            throw new TenantIsolationViolationException(tenantId, bound, traceId);
        }
    }

    /**
     * Rich DTO — all routing fields + business definition fields populated.
     * REQ-2026-006: encode includes {@code serviceId} (v1: 5-field); DTO
     * includes {@code maxConcurrency} (9th field) for caller-side weighted
     * load balancing.
     *
     * @param tenantId tenantId
     * @param row row
     * @return result
     * @since 0.1.0
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
