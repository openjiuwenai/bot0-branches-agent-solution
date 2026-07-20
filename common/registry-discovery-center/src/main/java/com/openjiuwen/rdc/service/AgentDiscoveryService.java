/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.service;

import java.util.List;

import com.openjiuwen.rdc.model.AgentCardDto;
import com.openjiuwen.rdc.model.RouteResolution;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;
import com.openjiuwen.rdc.tenant.TenantContext;

/**
 * agent-bus owned runtime route index lookup entry (HD3-001). The upper
 * Orchestrator / Gateway depends on this interface only; the persistence
 * form (single PostgreSQL in MVP, Consul + PGVector in phase 2) is
 * invisible to the caller.
 *
 * @since 2026-07-10
 *
 * <p>Authority: ADR-0160 decision 2 — revised by REQ-2026-006, then
 * <b>FEAT-016</b>. The registry PK evolves from {@code (tenant_id, agent_id,
 * service_id)} (REQ-2026-006) to {@code (tenant_id, agent_id, service_id,
 * instance_id)} (FEAT-016) so the same {@code agentId} + {@code serviceId}
 * can host N concrete runtime instances (horizontal scaling, blue/green
 * deploy). Discovery is now a <em>list</em> lookup with three query
 * dimensions: by {@code agentId}, by {@code serviceId}, and by
 * {@code capability}. Each returns all ONLINE/DEGRADED/DRAINING instances
 * matching the filter, each with its own opaque {@code routeHandle}. The
 * caller (Orchestrator / Gateway) selects an instance and resolves its
 * handle via {@link #resolveRouteHandle(String, String)}.
 *
 * <p>FEAT-016 changes (baseline-breaking):
 * <ul>
 *   <li>Added {@link #searchByServiceId(String, String, String)} — query by
 *       logical service identifier (host only).</li>
 *   <li>Added {@link #searchByCapability(String, String, String)} — query by
 *       capability tag (multi-value {@code capabilities} column).</li>
 *   <li>All three query methods ({@link #searchInstancesByAgentId},
 *       {@link #searchByServiceId}, {@link #searchByCapability}) now accept
 *       a nullable {@code contractVersion} filter: {@code null} = no filter;
 *       non-null = SQL {@code AND contract_version = :contractVersion}.</li>
 *   <li>DRAINING instances are now <em>included</em> in discovery results
 *       (was: excluded in REQ-2026-006). The caller sees DRAINING as a
 *       limited-availability health state and can route around it.</li>
 *   <li>{@link RouteResolution} adds {@code instanceId} as the first field
 *       (decoded from the v2: 6-field handle, not from DB; forwarding-layer
 *       only).</li>
 * </ul>
 *
 * <p>{@link #resolveRouteHandle(String, String)} (ADR-0160 decision 5) is the
 * <em>only</em> way the forwarding layer recovers a physical endpoint from an
 * opaque {@code routeHandle}. {@code RouteHandleCodec} stays internal to
 * {@code registry.runtime.discovery}; the encoding format evolved to
 * {@code v2:} prefix 6-field (adds {@code instanceId}) in FEAT-016 — old
 * {@code v1:} 5-field and no-prefix 4-field handles are rejected with
 * {@code IllegalArgumentException} (HTTP 400 {@code malformed_handle}).
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
 * <p><b>Anti-enumeration semantics</b>: all three query methods return an
 * empty list on no match (never {@code null}, never throws on not-found).
 * This prevents callers from distinguishing "no such tenant" from "no
 * matching instances" — both look the same to the caller.
 *
 * <p><b>Sort order</b>: all three query methods share the same sort:
 * {@code weight DESC, last_heartbeat DESC} — heavier weight and fresher
 * heartbeat first, so the caller's naive pick-first selection lands on a
 * healthy, high-capacity instance.
 *
 * <p>Pure Java — no Spring / JDBC / Jackson / Consul imports (ADR-0160
 * decision 1).
 */
public interface AgentDiscoveryService {
    /**
     * List all ONLINE/DEGRADED/DRAINING runtime instances registered under
     * the given {@code (tenantId, agentId)} pair. Each instance gets its own
     * opaque {@code routeHandle} (encoding {@code serviceId} +
     * {@code instanceId}); the caller selects one (e.g. round-robin, weighted
     * by {@code maxConcurrency}) and resolves it via
     * {@link #resolveRouteHandle(String, String)}.
     *
     * <p>FEAT-016: DRAINING is now included in discovery results (was
     * excluded in REQ-2026-006). The caller sees DRAINING as a
     * limited-availability health state. Added nullable {@code contractVersion}
     * filter.
     *
     * <p>Sort order: {@code weight DESC, last_heartbeat DESC} — heavier
     * weight and fresher heartbeat first, so the caller's naive pick-first
     * selection lands on a healthy, high-capacity instance.
     *
     * <p>Anti-enumeration: empty list on no match (never {@code null}).
     *
     * @param tenantId        registry key mandatory dimension; cross-tenant
     *                        fallback is forbidden (HD3-003). Mismatch with
     *                        the caller's {@link TenantContext} raises
     *                        {@link TenantIsolationViolationException}.
     * @param agentId         registry key mandatory dimension; the agent's
     *                        unique identifier within the tenant
     * @param contractVersion nullable contract version filter; {@code null}
     *                        = no filter; non-null = SQL
     *                        {@code AND contract_version = :contractVersion}
     * @return immutable list of {@link AgentCardDto}, one per matching
     *         instance; empty list if no ONLINE/DEGRADED/DRAINING entry
     *         matches (never {@code null})
     */
    List<AgentCardDto> searchInstancesByAgentId(String tenantId, String agentId, String contractVersion);

    /**
     * List all ONLINE/DEGRADED/DRAINING runtime instances registered under
     * the given {@code (tenantId, serviceId)} pair. FEAT-016 new query
     * dimension — callers query by logical service identifier (host only)
     * to discover all instances backing a service.
     *
     * <p>Sort order: {@code weight DESC, last_heartbeat DESC} (shared with
     * {@link #searchInstancesByAgentId}).
     *
     * <p>Anti-enumeration: empty list on no match (never {@code null}).
     *
     * @param tenantId        registry key mandatory dimension
     * @param serviceId       logical service identifier (host only)
     * @param contractVersion nullable contract version filter
     * @return immutable list of {@link AgentCardDto}; empty list on no match
     */
    List<AgentCardDto> searchByServiceId(String tenantId, String serviceId, String contractVersion);

    /**
     * List all ONLINE/DEGRADED/DRAINING runtime instances that declare the
     * given {@code capability} in their {@code capabilities} array column.
     * FEAT-016 new query dimension — replaces the free-text capability search
     * removed in REQ-2026-004 with an exact-match array-contains query.
     *
     * <p>Sort order: {@code weight DESC, last_heartbeat DESC} (shared with
     * {@link #searchInstancesByAgentId}).
     *
     * <p>Anti-enumeration: empty list on no match (never {@code null}).
     *
     * @param tenantId        registry key mandatory dimension
     * @param capability      capability tag to match (exact string)
     * @param contractVersion nullable contract version filter
     * @return immutable list of {@link AgentCardDto}; empty list on no match
     */
    List<AgentCardDto> searchByCapability(String tenantId, String capability, String contractVersion);

    /**
     * Resolve an opaque {@code routeHandle} into a {@link RouteResolution}
     * for the forwarding delivery infrastructure. The Orchestrator business
     * logic never calls this method — only the forwarding layer does
     * (HD3-006).
     *
     * <p>FEAT-016: the decoded handle now carries {@code instanceId} (v2:
     * 6-field); the repository lookup uses the 4-field PK
     * {@code (tenantId, agentId, serviceId, instanceId)}.
     *
     * @param routeHandle opaque handle produced by a prior
     *                    {@code searchInstancesByAgentId} /
     *                    {@code searchByServiceId} /
     *                    {@code searchByCapability} call
     * @param tenantId    tenant id of the resolving caller; mismatch with the
     *                    tenant encoded in the handle raises
     *                    {@link TenantIsolationViolationException}
     * @return resolved instanceId / endpoint / route key / contract version
     * @throws IllegalArgumentException             if the handle is malformed
     *         (including old v1: 5-field and no-prefix 4-field handles from
     *         pre-FEAT-016 codec)
     * @throws TenantIsolationViolationException   if {@code tenantId} does not
     *         match the handle's encoded tenant
     */
    RouteResolution resolveRouteHandle(String routeHandle, String tenantId);
}
