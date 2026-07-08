package com.openjiuwen.rdc.registry.runtime.persistence.jdbc;

import com.openjiuwen.rdc.spi.registry.AgentRegistryEntry;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the agent registry MVP table. Implemented solely by
 * {@link JdbcAgentRegistryRepository} — the only subpackage allowed to import
 * {@code java.sql} / {@code javax.sql} / {@code org.springframework.jdbc}
 * (ADR-0160 decision 4). The {@code api} / {@code discovery} / {@code health}
 * subpackages call this port and never touch JDBC directly.
 *
 * <p>Authority: ADR-0160 decision 4 + HD3-001/003/004/005/006. The port
 * returns <em>raw row snapshots</em> ({@link RegistryRow}) rather than
 * {@code AgentCardDto} — the {@code AgentDiscoveryService} implementation in
 * {@code registry.runtime.discovery} is responsible for encoding the opaque
 * {@code routeHandle} via {@code RouteHandleCodec} and assembling the DTO so
 * the route handle format stays encapsulated in the discovery layer
 * (ADR-0160 decision 5).
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
 * depends only on {@link AgentRegistryEntry} from {@code spi.registry} and on the
 * nested record types declared below.
 */
public interface AgentRegistryRepository {

    /**
     * Upsert (insert or replace) a registered agent entry. On conflict
     * {@code (tenant_id, agent_id)} the existing row is overwritten and the
     * status reset to {@code ONLINE} with a fresh {@code last_heartbeat}
     * (the agent re-registers on restart). The exception is
     * {@code DRAINING}: an entry that the operator has marked
     * {@code DRAINING} (graceful drain in progress) is preserved across
     * re-registration, so an agent restart during drain does not pull the
     * entry back to {@code ONLINE} and re-route traffic to it (PR #389
     * review issue #7).
     *
     * @param entry           registry entry from the {@code POST /register} body
     * @param a2aAgentCardJson pre-serialized JSON of
     *                        {@link AgentRegistryEntry#getA2aAgentCard()};
     *                        {@code null} when the entry carries no A2A card.
     *                        Serialization is the caller's concern (HTTP
     *                        boundary in {@code registry.runtime.api}) so this
     *                        port stays Jackson-free (ADR-0160 decision 3/5).
     */
    void upsert(AgentRegistryEntry entry, String a2aAgentCardJson);

    /**
     * Delete a registered agent.
     *
     * @return {@code true} if a row was deleted, {@code false} if no entry
     *         existed for {@code (tenantId, agentId)}
     */
    boolean delete(String tenantId, String agentId);

    /**
     * Scan {@code ONLINE} entries whose {@code last_heartbeat} is older than
     * {@code staleBeforeMillis} for the health-probe scheduler
     * ({@code MvpHealthProbeScheduler}, HD3-004 lease/TTL). Returns at most
     * {@code limit} rows ordered by oldest heartbeat first.
     */
    List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit);

    /**
     * Update the lifecycle status of a registered agent and optionally
     * refresh {@code last_heartbeat}. Used by the health-probe scheduler
     * (5xx → {@code DEGRADED}, success → {@code ONLINE}).
     *
     * @param newStatus         one of {@code ONLINE} / {@code DEGRADED} /
     *                          {@code DRAINING} / {@code OFFLINE}
     * @param refreshHeartbeat  {@code true} stamps {@code last_heartbeat =
     *                          CURRENT_TIMESTAMP}; {@code false} leaves it
     *                          untouched (used when downgrading)
     * @return {@code true} if a row was updated
     */
    boolean updateStatus(String tenantId, String agentId, String newStatus, boolean refreshHeartbeat);

    /**
     * Method A discovery SQL — tsvector-ranked natural-language intent search.
     * Filters by {@code tenant_id = :tenantId} AND
     * {@code status IN ('ONLINE','DEGRADED')} AND
     * {@code last_heartbeat >= NOW() - INTERVAL '15 seconds'} (HD3-004
     * visibility window) AND optional {@code contract_version = :contractVersion}
     * AND (when {@code userQuery} is non-null)
     * {@code search_tsv @@ websearch_to_tsquery} (PR #389 review issue #9:
     * keyword-style OR, no adjacency requirement).
     * Ordered by ts_rank DESC, weight DESC.
     *
     * @param userQuery        natural-language intent; {@code null} = weight-only
     *                         ranking (no tsvector filter). Non-null is
     *                         tokenised via {@code websearch_to_tsquery}
     *                         (PR #389 review issue #9: keyword-style OR,
     *                         no adjacency requirement).
     * @param contractVersion  version filter; {@code null} = no filter,
     *                         non-null = exact match
     * @param topK             upper bound on returned candidates
     */
    List<RegistryRow> searchByIntent(String tenantId, String userQuery, String contractVersion, int topK);

    /**
     * Method B discovery SQL — capability-scoped, optionally tsvector-ranked.
     * Same tenant / status / heartbeat / contract-version filters as
     * {@link #searchByIntent}; additionally scoped by
     * {@code capability = :capability}.
     */
    List<RegistryRow> searchByCapability(String tenantId, String capability, String userQuery,
                                          String contractVersion, int topK);

    /**
     * Resolve the physical endpoint for an opaque {@code routeHandle}.
     * Called by {@code AgentDiscoveryService.resolveRouteHandle} after
     * {@code RouteHandleCodec.decode} has extracted the {@code (tenantId,
     * agentId)} pair. Returns {@code Optional#empty()} when no row matches —
     * the discovery service maps that to {@code entry_not_found} (HTTP 404).
     *
     * <p>Note: tenant mismatch is detected earlier by {@code RouteHandleCodec}
     * / the discovery service (the encoded tenant is compared to the caller's
     * tenant before this lookup runs); this method always scopes by the
     * caller's {@code tenantId}.
     */
    Optional<EndpointEntry> findEndpoint(String tenantId, String agentId);

    /**
     * Raw row snapshot mirroring {@code agent_registry_mvp} columns. The
     * discovery service assembles {@code AgentCardDto} from this snapshot +
     * the encoded route handle. Method A populates every field; Method B
     * reads the row but leaves the business definition fields
     * ({@code agentName} / {@code agentType}) out of the resulting DTO.
     * REQ-2026-001 removed {@code serviceId} / {@code systemProfile} /
     * {@code toolSchemas} — the A2A standard card now carries the equivalent
     * metadata via {@link AgentRegistryEntry#getA2aAgentCard()}.
     */
    record RegistryRow(
            String agentId,
            String agentName,
            String agentType,
            String capability,
            String routeKey,
            String contractVersion,
            String capabilityVersion,
            int weight,
            String region,
            String status
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
     * scheduler needs to issue an HTTP {@code GET {endpoint_url}/health}.
     */
    record ProbeTarget(String tenantId, String agentId, String endpointUrl) {
    }
}
