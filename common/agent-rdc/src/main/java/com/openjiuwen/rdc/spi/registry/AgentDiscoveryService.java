package com.openjiuwen.rdc.spi.registry;

import java.util.List;

/**
 * agent-bus owned runtime route index lookup entry (HD3-001). The upper
 * Orchestrator / Gateway depends on this interface only; the persistence
 * form (single PostgreSQL + tsvector in MVP, Consul + PGVector in phase 2)
 * is invisible to the caller.
 *
 * <p>Authority: ADR-0160 decision 2 — <b>dual-method discovery contract</b>.
 * Two {@code discoverBestAgents} overloads serve exploratory and
 * capability-scoped callers from one SPI:
 * <ul>
 *   <li><b>Method A</b> {@code discoverBestAgents(tenantId, userQuery, contractVersion, topK)}
 *       — natural-language intent discovery. Returns <em>rich</em>
 *       {@link AgentCardDto} (business definition fields populated) for
 *       exploratory callers (Orchestrator / Gateway) that do not know the
 *       capability name ahead of time.</li>
 *   <li><b>Method B</b> {@code discoverBestAgents(tenantId, capability, userQuery, contractVersion, topK)}
 *       — capability-scoped discovery. Returns <em>minimal</em>
 *       {@link AgentCardDto} (business definition fields {@code null}, ICD 5
 *       routing fields only) for callers that already know which capability
 *       they want and only need the routing handle.</li>
 * </ul>
 *
 * <p>{@code contractVersion} filter semantics (both methods):
 * {@code null} = no version filter; non-null = exact match; empty result on
 * version mismatch = silent {@code version_unavailable} result, no exception
 * (decision deferred to phase 2 H2/H3 review — PRD line 152).
 *
 * <p>{@code userQuery} semantics (both methods): {@code null} = weight-only
 * ranking; non-null = tsvector ranking (MVP) / vector ranking (phase 2).
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
     * Method A — natural-language intent discovery. Rich DTO (business
     * definition fields populated).
     *
     * @param tenantId         registry key mandatory dimension; cross-tenant
     *                         fallback is forbidden (HD3-003). Mismatch with
     *                         the caller's {@link TenantContext} raises
     *                         {@link TenantIsolationViolationException}.
     * @param userQuery        natural-language intent; {@code null} = weight-only
     *                         ranking
     * @param contractVersion  version filter; {@code null} = no filter,
     *                         non-null = exact match, empty result on mismatch
     * @param topK             upper bound on returned candidates
     * @return list of {@link AgentCardDto} ordered by relevance; never
     *         {@code null}, possibly empty
     */
    List<AgentCardDto> discoverBestAgents(String tenantId,
                                          String userQuery,
                                          String contractVersion,
                                          int topK);

    /**
     * Method B — capability-scoped discovery. Minimal DTO (business
     * definition fields {@code null}, ICD 5 routing fields only).
     *
     * @param tenantId         registry key mandatory dimension (HD3-003)
     * @param capability       mandatory capability name to scope the search
     * @param userQuery        optional natural-language intent for tsvector
     *                         ranking within the capability; {@code null} =
     *                         weight-only ranking
     * @param contractVersion  version filter; same semantics as Method A
     * @param topK             upper bound on returned candidates
     * @return list of {@link AgentCardDto} ordered by relevance; never
     *         {@code null}, possibly empty
     */
    List<AgentCardDto> discoverBestAgents(String tenantId,
                                          String capability,
                                          String userQuery,
                                          String contractVersion,
                                          int topK);

    /**
     * Resolve an opaque {@code routeHandle} into a {@link RouteResolution}
     * for the forwarding delivery infrastructure. The Orchestrator business
     * logic never calls this method — only the forwarding layer does
     * (HD3-006).
     *
     * @param routeHandle opaque handle produced by a prior
     *                    {@code discoverBestAgents} call
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
