package com.openjiuwen.rdc.spi.registry;

import java.util.List;

/**
 * agent-bus owned runtime route index lookup entry (HD3-001). The upper
 * Orchestrator / Gateway depends on this interface only; the persistence
 * form (single PostgreSQL in MVP, Consul + PGVector in phase 2) is
 * invisible to the caller.
 *
 * <p>Authority: ADR-0160 decision 2 — <b>revised by REQ-2026-006</b>. The
 * registry PK evolves from {@code (tenant_id, agent_id)} to
 * {@code (tenant_id, agent_id, service_id)} so the same {@code agentId} can
 * host N runtime instances (horizontal scaling). Discovery is now a
 * <em>list</em> lookup: {@link #searchInstancesByAgentId(String, String)}
 * returns all ONLINE/DEGRADED instances for a given {@code agentId}, each
 * with its own opaque {@code routeHandle}. The caller (Orchestrator /
 * Gateway) selects an instance and resolves its handle via
 * {@link #resolveRouteHandle(String, String)}.
 *
 * <p>REQ-2026-006 removed {@code searchByAgentId(String, String)} (the
 * single-value {@code Optional<AgentCardDto>} lookup introduced in
 * REQ-2026-004) — single-value lookup cannot represent N instances.
 * Baseline-breaking: no deprecated shim; callers migrate to
 * {@link #searchInstancesByAgentId(String, String)} which returns
 * {@code List<AgentCardDto>} (empty list = agent_not_found).
 *
 * <p>{@link #resolveRouteHandle(String, String)} (ADR-0160 decision 5) is the
 * <em>only</em> way the forwarding layer recovers a physical endpoint from an
 * opaque {@code routeHandle}. {@code RouteHandleCodec} stays internal to
 * {@code registry.runtime.discovery}; the encoding format evolved to
 * {@code v1:} prefix 5-field (adds {@code serviceId}) in REQ-2026-006 —
 * old 4-field handles are rejected with {@code IllegalArgumentException}
 * (HTTP 400 {@code malformed_handle}).
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
     * List all ONLINE/DEGRADED runtime instances registered under the given
     * {@code (tenantId, agentId)} pair. Each instance gets its own opaque
     * {@code routeHandle} (encoding {@code serviceId}); the caller selects
     * one (e.g. round-robin, weighted by {@code maxConcurrency}) and
     * resolves it via {@link #resolveRouteHandle(String, String)}.
     *
     * <p>Replaces {@code searchByAgentId(String, String)} (REQ-2026-004
     * single-value lookup, removed in REQ-2026-006). Baseline-breaking:
     * no deprecated shim; callers migrate from {@code Optional<AgentCardDto>}
     * to {@code List<AgentCardDto>} (empty list = agent_not_found).
     *
     * <p>Status filter: only {@code ONLINE} and {@code DEGRADED} entries
     * are returned; {@code DRAINING} / {@code OFFLINE} entries are excluded
     * (treated as not-discoverable from the caller's perspective — the
     * health-probe scheduler still sees them for state transitions).
     *
     * <p>Sort order: {@code weight DESC, last_heartbeat DESC} — heavier
     * weight and fresher heartbeat first, so the caller's naive
     * pick-first selection lands on a healthy, high-capacity instance.
     *
     * @param tenantId  registry key mandatory dimension; cross-tenant
     *                  fallback is forbidden (HD3-003). Mismatch with the
     *                  caller's {@link TenantContext} raises
     *                  {@link TenantIsolationViolationException}.
     * @param agentId   registry key mandatory dimension; the agent's
     *                  unique identifier within the tenant
     * @return immutable list of {@link AgentCardDto}, one per matching
     *         instance; empty list if no ONLINE/DEGRADED entry matches
     *         (never {@code null})
     */
    List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId);

    /**
     * Resolve an opaque {@code routeHandle} into a {@link RouteResolution}
     * for the forwarding delivery infrastructure. The Orchestrator business
     * logic never calls this method — only the forwarding layer does
     * (HD3-006).
     *
     * @param routeHandle opaque handle produced by a prior
     *                    {@code searchInstancesByAgentId} call
     * @param tenantId    tenant id of the resolving caller; mismatch with the
     *                    tenant encoded in the handle raises
     *                    {@link TenantIsolationViolationException}
     * @return resolved endpoint / route key / contract version
     * @throws IllegalArgumentException             if the handle is malformed
     *         (including old 4-field handles from pre-REQ-2026-006 codec)
     * @throws TenantIsolationViolationException   if {@code tenantId} does not
     *         match the handle's encoded tenant
     */
    RouteResolution resolveRouteHandle(String routeHandle, String tenantId);
}
