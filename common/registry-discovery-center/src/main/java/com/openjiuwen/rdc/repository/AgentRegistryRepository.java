/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.FrameworkType;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the agent registry MVP table. Implemented solely by
 * {@link JdbcAgentRegistryRepository} — the only subpackage allowed to import
 * {@code java.sql} / {@code javax.sql} / {@code org.springframework.jdbc}
 * (ADR-0160 decision 4). The {@code api} / {@code discovery} / {@code health}
 * subpackages call this port and never touch JDBC directly.
 *
 * <p>Authority: ADR-0160 decision 4 + HD3-001/003/004/005/006, revised by
 * REQ-2026-004, REQ-2026-006, then <b>FEAT-016</b>. The port returns
 * <em>raw row snapshots</em> ({@link RegistryRow}) rather than
 * {@code AgentCardDto} — the {@code AgentDiscoveryService} implementation in
 * {@code registry.runtime.discovery} is responsible for encoding the opaque
 * {@code routeHandle} via {@code RouteHandleCodec} and assembling the DTO so
 * the route handle format stays encapsulated in the discovery layer
 * (ADR-0160 decision 5).
 *
 * <p>FEAT-016 changes (baseline-breaking):
 * <ul>
 *   <li>Registry PK evolves to {@code (tenant_id, agent_id, service_id,
 *       instance_id)} — the same {@code agentId} + {@code serviceId} can host
 *       N concrete instances (host-port). The 3-field
 *       {@code delete(tenantId, agentId, serviceId)} now deletes <em>all</em>
 *       instances under the triple; a new 4-field
 *       {@link #delete(String, String, String, String)} deletes a single
 *       instance.</li>
 *   <li>{@link #updateStatus(StatusUpdate)}
 *       now takes {@code instanceId} — health-probe results are scoped per
 *       concrete instance.</li>
 *   <li>{@link #listByAgentId(String, String, String)} now takes a nullable
 *       {@code contractVersion} filter; DRAINING is now included in the
 *       status filter (was excluded in REQ-2026-006).</li>
 *   <li>Added {@link #listByServiceId(String, String, String)} — query by
 *       logical service identifier.</li>
 *   <li>Added {@link #listByCapability(String, String, String)} — query by
 *       capability tag (exact-match array-contains).</li>
 *   <li>{@link #findEndpoint(String, String, String, String)} now takes
 *       {@code instanceId} — the codec decodes {@code instanceId} from the
 *       v2: 6-field handle and passes the 4-field PK.</li>
 *   <li>{@link RegistryRow} record adds {@code instanceId} (2nd field) and
 *       {@code capabilities} ({@code List<String>}, last field) — 13 fields
 *       total.</li>
 *   <li>{@link ProbeTarget} record adds {@code instanceId} — the health-probe
 *       scheduler needs it to call {@link #updateStatus} with the right
 *       instance scope.</li>
 *   <li>Upsert {@code ON CONFLICT} evolves to
 *       {@code (tenant_id, agent_id, service_id, instance_id)} —
 *       instance-level idempotent overwrite.</li>
 * </ul>
 *
 * <p>Tenant isolation: every method takes {@code tenantId} as the first
 * parameter and the implementation MUST scope every SQL statement by
 * {@code WHERE tenant_id = :tenantId} (Rule R-C.c application-layer hard
 * isolation). The {@link JdbcAgentRegistryRepository} additionally sets the
 * transaction-scoped {@code app.tenant_id} so the §RLS defence-in-depth
 * policy is bound for the duration of each call.
 *
 * <p>Pure Java — the port itself imports no JDBC / Spring type so callers in
 * {@code api} / {@code discovery} / {@code health} stay JDBC-free. The port
 * depends only on {@link AgentRegistryEntry} + {@link FrameworkType} from
 * {@code spi.registry} and on the nested record types declared below.
 *
 * @since 2026-07-10
 */
public interface AgentRegistryRepository {
    /**
     * Upsert (insert or replace) a registered agent entry. On conflict
     * {@code (tenant_id, agent_id, service_id, instance_id)} the existing row
     * is overwritten and the status reset to {@code ONLINE} with a fresh
     * {@code last_heartbeat} (the agent re-registers on restart). The
     * exception is {@code DRAINING}: an entry that the operator has marked
     * {@code DRAINING} (graceful drain in progress) is preserved across
     * re-registration, so an agent restart during drain does not pull the
     * entry back to {@code ONLINE} and re-route traffic to it (PR #389
     * review issue #7).
     *
     * <p>FEAT-016: {@code entry.instanceId} (server-derived from
     * {@code endpointUrl} via {@code InstanceIdCodec}) is the fourth PK
     * column. {@code ON CONFLICT} scopes to the concrete-instance level so N
     * instances of the same {@code agentId} + {@code serviceId} upsert
     * independently.
     *
     * @param entry           registry entry from the {@code POST /register} body
     *                        (push mode) or from pull-based bootstrap
     *                        ({@code PullRegistrationBootstrap}, pull mode).
     *                        {@code entry.serviceId} + {@code entry.instanceId}
     *                        MUST be populated by the caller via
     *                        {@code ServiceIdCodec.applyTo(entry)} +
     *                        {@code InstanceIdCodec.applyTo(entry)} before
     *                        invoking upsert.
     * @param a2aAgentCardJson pre-serialized JSON of
     *                        {@link AgentRegistryEntry#getA2aAgentCard()};
     *                        {@code null} when the entry carries no A2A card.
     *                        Serialization is the caller's concern (HTTP
     *                        boundary in {@code registry.runtime.api} / pull
     *                        bootstrap) so this port stays Jackson-free
     *                        (ADR-0160 decision 3/5).
     */
    void upsert(AgentRegistryEntry entry, String a2aAgentCardJson);

    /**
     * Delete <em>all</em> registered instances for the given
     * {@code (tenantId, agentId)} pair. Semantic generalization in
     * REQ-2026-006: previously deleted a single row (PK was
     * {@code (tenant_id, agent_id)}); now deletes every instance row
     * matching the pair.
     *
     * @param tenantId tenant dimension of the registry PK
     * @param agentId  agent dimension of the registry PK
     * @return {@code true} if at least one row was deleted, {@code false}
     *         if no entry existed for {@code (tenantId, agentId)}
     */
    boolean delete(String tenantId, String agentId);

    /**
     * Delete <em>all</em> registered instances for the given triple
     * {@code (tenantId, agentId, serviceId)}. FEAT-016: with the 4-field PK,
     * this deletes every concrete instance under the triple (was
     * single-instance delete in REQ-2026-006).
     *
     * @param tenantId tenant dimension of the registry PK
     * @param agentId  agent dimension of the registry PK
     * @param serviceId logical service identifier
     * @return {@code true} if at least one row was deleted, {@code false}
     *         if no entry existed for the triple
     */
    boolean delete(String tenantId, String agentId, String serviceId);

    /**
     * Delete a single registered instance by 4-field PK
     * {@code (tenantId, agentId, serviceId, instanceId)}. FEAT-016 new —
     * rolling deploy of a single replica must not evict other replicas of
     * the same {@code serviceId}.
     *
     * @param tenantId   tenant dimension of the registry PK
     * @param agentId    agent dimension of the registry PK
     * @param serviceId  logical service identifier
     * @param instanceId concrete instance identifier (host-port)
     * @return {@code true} if a row was deleted, {@code false} if no entry
     *         existed for the 4-field PK
     */
    boolean delete(String tenantId, String agentId, String serviceId, String instanceId);

    /**
     * Scan {@code ONLINE} / {@code DEGRADED} entries whose
     * {@code last_heartbeat} is older than {@code staleBeforeMillis} for the
     * health-probe scheduler ({@code MvpHealthProbeScheduler}, HD3-004
     * lease/TTL). Returns at most {@code limit} rows ordered by oldest
     * heartbeat first.
     *
     * <p>Pull-based registration entries (inserted by
     * {@code PullRegistrationBootstrap}) are automatically picked up by this
     * scan — they live in the same {@code agent_registry_mvp} table and
     * follow the same heartbeat / status lifecycle as push-based entries.
     *
     * @param staleBeforeMillis epoch-millis cutoff; rows whose
     *                          {@code last_heartbeat} is older are returned
     * @param limit             max number of rows to return
     * @return immutable list of {@link ProbeTarget}; empty list on no match
     */
    List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit);

    /**
     * Update the lifecycle status of a single instance identified by the
     * 4-field PK {@code (tenantId, agentId, serviceId, instanceId)} and
     * optionally refresh {@code last_heartbeat}. Used by the health-probe
     * scheduler (5xx → {@code DEGRADED}, success → {@code ONLINE}).
     *
     * <p>FEAT-016: {@code instanceId} parameter added — health-probe results
     * are scoped per concrete instance, not per {@code serviceId}. A failed
     * probe on one replica must not degrade other replicas of the same
     * {@code serviceId}.
     *
     * @param update the status-update request (PK + new status + heartbeat flag)
     * @return {@code true} if a row was updated
     */
    boolean updateStatus(StatusUpdate update);

    /**
     * Immutable status-update request for {@link #updateStatus(StatusUpdate)}.
     * Carries the 4-field registry PK plus the new lifecycle status and the
     * heartbeat-refresh flag.
     */
    record StatusUpdate(
            String tenantId,
            String agentId,
            String serviceId,
            String instanceId,
            String newStatus,
            boolean shouldRefreshHeartbeat) {
    }

    /**
     * List all ONLINE/DEGRADED/DRAINING instances for the given
     * {@code (tenantId, agentId)} pair. FEAT-016: DRAINING is now included
     * (was excluded in REQ-2026-006). Added nullable {@code contractVersion}
     * filter.
     *
     * <p>Sort order: {@code weight DESC, last_heartbeat DESC} — matches the
     * discovery service's DTO ordering so the caller (Orchestrator) sees the
     * healthiest, highest-capacity instances first.
     *
     * <p>Anti-enumeration: empty list on no match (never {@code null}).
     *
     * @param tenantId        tenant dimension of the registry PK
     * @param agentId         agent dimension of the registry PK
     * @param contractVersion nullable contract version filter; {@code null}
     *                        = no filter; non-null = SQL
     *                        {@code AND contract_version = :contractVersion}
     * @return immutable list of {@link RegistryRow}, one per matching
     *         instance; empty list if no ONLINE/DEGRADED/DRAINING entry
     *         matches (never {@code null})
     */
    List<RegistryRow> listByAgentId(String tenantId, String agentId, String contractVersion);

    /**
     * List all ONLINE/DEGRADED/DRAINING instances for the given
     * {@code (tenantId, serviceId)} pair. FEAT-016 new — query by logical
     * service identifier.
     *
     * <p>Sort order: {@code weight DESC, last_heartbeat DESC} (shared with
     * {@link #listByAgentId}).
     *
     * <p>Anti-enumeration: empty list on no match (never {@code null}).
     *
     * @param tenantId        tenant dimension of the registry PK
     * @param serviceId       logical service identifier
     * @param contractVersion nullable contract version filter
     * @return immutable list of {@link RegistryRow}; empty list on no match
     */
    List<RegistryRow> listByServiceId(String tenantId, String serviceId, String contractVersion);

    /**
     * List all ONLINE/DEGRADED/DRAINING instances that declare the given
     * {@code capability} in their {@code capabilities} array column. FEAT-016
     * new — exact-match array-contains query.
     *
     * <p>Sort order: {@code weight DESC, last_heartbeat DESC} (shared with
     * {@link #listByAgentId}).
     *
     * <p>Anti-enumeration: empty list on no match (never {@code null}).
     *
     * @param tenantId        tenant dimension of the registry PK
     * @param capability      capability tag to match
     * @param contractVersion nullable contract version filter
     * @return immutable list of {@link RegistryRow}; empty list on no match
     */
    List<RegistryRow> listByCapability(String tenantId, String capability, String contractVersion);

    /**
     * Resolve the physical endpoint for a single instance identified by the
     * 4-field PK {@code (tenantId, agentId, serviceId, instanceId)}. Called
     * by {@code AgentDiscoveryService.resolveRouteHandle} after
     * {@code RouteHandleCodec.decode} has extracted the 4 fields from the
     * v2: 6-field handle. Returns {@code Optional#empty()} when no row
     * matches — the discovery service maps that to {@code entry_not_found}
     * (HTTP 404).
     *
     * <p>FEAT-016: {@code instanceId} parameter added — the codec now
     * decodes {@code instanceId} from the v2: 6-field handle and passes the
     * 4-field PK.
     *
     * <p>Note: tenant mismatch is detected earlier by {@code RouteHandleCodec}
     * / the discovery service (the encoded tenant is compared to the caller's
     * tenant before this lookup runs); this method always scopes by the
     * caller's {@code tenantId}.
     *
     * @param tenantId   tenant dimension of the registry PK
     * @param agentId    agent dimension of the registry PK
     * @param serviceId  logical service identifier
     * @param instanceId concrete instance identifier (host-port)
     * @return {@link EndpointEntry} when a row matches; {@code Optional#empty()}
     *         when no row matches
     */
    Optional<EndpointEntry> findEndpoint(String tenantId, String agentId,
                                         String serviceId, String instanceId);

    /**
     * Raw row snapshot mirroring {@code agent_registry_mvp} columns. The
     * discovery service assembles {@code AgentCardDto} from this snapshot +
     * the encoded route handle. FEAT-016 adds {@code instanceId} (2nd field,
     * server-derived host-port) and {@code capabilities} (last field,
     * {@code List<String>} from the VARCHAR(64)[] column).
     */
    record RegistryRow(
            String serviceId,
            String instanceId,
            String agentId,
            String agentName,
            FrameworkType frameworkType,
            String routeKey,
            String contractVersion,
            String capabilityVersion,
            int weight,
            String region,
            int maxConcurrency,
            String status,
            List<String> capabilities
    ) {
    }

    /**
     * Endpoint resolution result — the three fields the forwarding layer
     * needs to deliver. Mirrors {@code RouteResolution} but lives inside the
     * persistence port so the discovery service can map between them without
     * exposing JDBC types at the SPI boundary.
     */
    record EndpointEntry(String endpointUrl, String routeKey, String contractVersion) {
    }

    /**
     * Probe target for the health-probe scheduler — only the fields the
     * scheduler needs to issue an HTTP {@code GET {endpoint_url}/health} and
     * then call {@link #updateStatus} with the right instance scope.
     * FEAT-016 adds {@code instanceId} so the scheduler can target a specific
     * concrete instance.
     */
    record ProbeTarget(
            String tenantId, String agentId, String serviceId,
            String instanceId, String endpointUrl) {
    }
}
