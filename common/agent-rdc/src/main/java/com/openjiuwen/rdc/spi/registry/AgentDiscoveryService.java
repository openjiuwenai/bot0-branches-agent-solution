package com.openjiuwen.rdc.spi.registry;

import java.util.Optional;

/**
 * agent-bus owned runtime route index lookup entry (HD3-001). The upper
 * Orchestrator / Gateway depends on this interface only; the persistence
 * form (single PostgreSQL in MVP, Consul + PGVector in phase 2) is
 * invisible to the caller.
 *
 * <p>Authority: ADR-0160 decision 2 — <b>revised by REQ-2026-004</b>. The
 * dual-method discovery contract (Method A free-text + Method B
 * capability-scoped) is removed; discovery collapses to
 * {@link #searchByAgentId(String, String)} single-value point lookup.
 * The free-text search infrastructure ({@code search_tsv} column + GIN
 * index + {@code ts_rank}) is removed in V4 — A2A AgentCard carries no
 * {@code capability} concept, so capability-scoped discovery cannot be
 * populated from pull-based registration.
 *
 * <p>{@link #resolveRouteHandle(String, String)} (ADR-0160 decision 5) is the
 * <em>only</em> way the forwarding layer recovers a physical endpoint from an
 * opaque {@code routeHandle}. {@code RouteHandleCodec} stays internal to
 * {@code registry.runtime.discovery}; the encoding format can evolve
 * ({@code v1:} prefix in phase 2) without breaking cross-module consumers.
 *
 * <p>Failure modes for {@code resolveRouteHandle}:
 * <ul>
 *   <li>malformed handle → {@link IllegalArgumentException} (HTTP 400)</li>
 *   <li>handle points to non-existent entry → {@code entry_not_found} (HTTP 404)
 *       — concrete exception type is implementation-specific; the MVP
 *       controller maps it.</li>
 *   <li>tenant mismatch → {@link TenantIsolationViolationException}
 *       (HTTP 400 {@code tenant_isolation_violation})</li>
 * </ul>
 *
 * <p>Pure Java — no Spring / JDBC / Jackson / Consul imports (ADR-0160
 * decision 1).
 */
public interface AgentDiscoveryService {

    /**
     * Single-value point lookup by registry primary key
     * {@code (tenantId, agentId)}. Replaces the dual Method A / Method B
     * {@code discoverBestAgents} overloads removed in REQ-2026-004.
     *
     * <p>Returns {@link Optional#empty()} when no row matches — the caller
     * (Orchestrator / Gateway) maps that to {@code agent_not_found}. A
     * matched row returns a rich {@link AgentCardDto} with all ICD 5
     * routing fields populated; business definition fields
     * ({@code agentName} / {@code frameworkType}) are also populated
     * (single-value lookup has no "minimal DTO" variant).
     *
     * <p>Status filter: only {@code ONLINE} and {@code DEGRADED} entries
     * are returned; {@code DRAINING} / {@code OFFLINE} entries return
     * {@link Optional#empty()} (treated as not-found from the discovery
     * caller's perspective — the health-probe scheduler still sees them
     * for state transitions).
     *
     * @param tenantId  registry key mandatory dimension; cross-tenant
     *                  fallback is forbidden (HD3-003). Mismatch with the
     *                  caller's {@link TenantContext} raises
     *                  {@link TenantIsolationViolationException}.
     * @param agentId   registry key mandatory dimension; the agent's
     *                  unique identifier within the tenant
     * @return {@link Optional#empty()} if no ONLINE/DEGRADED entry matches,
     *         otherwise a populated {@link AgentCardDto}
     */
    Optional<AgentCardDto> searchByAgentId(String tenantId, String agentId);

    /**
     * Resolve an opaque {@code routeHandle} into a {@link RouteResolution}
     * for the forwarding delivery infrastructure. The Orchestrator business
     * logic never calls this method — only the forwarding layer does
     * (HD3-006).
     *
     * @param routeHandle opaque handle produced by a prior
     *                    {@code searchByAgentId} call
     * @param tenantId    tenant id of the resolving caller; mismatch with the
     *                    tenant encoded in the handle raises
     *                    {@link TenantIsolationViolationException}
     * @return resolved endpoint / route key / contract version
     * @throws IllegalArgumentException             if the handle is malformed
     * @throws TenantIsolationViolationException   if {@code tenantId} does not
     *         match the handle's encoded tenant
     */
    RouteResolution resolveRouteHandle(String routeHandle, String tenantId);
}
