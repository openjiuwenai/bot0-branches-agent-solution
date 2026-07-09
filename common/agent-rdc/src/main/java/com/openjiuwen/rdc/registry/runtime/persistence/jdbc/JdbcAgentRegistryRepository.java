package com.openjiuwen.rdc.registry.runtime.persistence.jdbc;

import com.openjiuwen.rdc.spi.registry.AgentRegistryEntry;
import com.openjiuwen.rdc.spi.registry.FrameworkType;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Postgres JDBC adapter for {@code agent_registry_mvp} (Stage 4 / ADR-0160
 * decision 4). The only class in {@code com.openjiuwen.rdc.registry.**}
 * allowed to import {@code java.sql} / {@code javax.sql} /
 * {@code org.springframework.jdbc.*} — enforced at test time by
 * {@code AgentRdcRegistryJdbcPurityTest} (S5).
 *
 * <h2>SQL contract (REQ-2026-006 revised)</h2>
 * <ul>
 *   <li><b>upsert</b> — {@code INSERT ... ON CONFLICT (tenant_id, agent_id,
 *       service_id) DO UPDATE SET ...} ; an agent restart overwrites the
 *       prior entry and resets {@code status = 'ONLINE'} +
 *       {@code last_heartbeat = NOW()}, EXCEPT when the prior status is
 *       {@code DRAINING} (operator-initiated graceful drain) — DRAINING is
 *       preserved so a restart during drain does not re-route traffic to
 *       the agent (PR #389 review issue #7). REQ-2026-006: {@code service_id}
 *       is the third PK column (server-derived from {@code endpoint_url}
 *       via {@code ServiceIdCodec}); {@code ON CONFLICT} scopes to instance
 *       level so N instances of the same agentId upsert independently.</li>
 *   <li><b>delete(tenantId, agentId)</b> — {@code DELETE WHERE tenant_id =
 *       :tenantId AND agent_id = :agentId}; semantic generalization in
 *       REQ-2026-006: deletes ALL instances for the pair (was single-row
 *       delete when PK was (tenant_id, agent_id)). Backward compatible —
 *       callers that previously deleted one row now delete all instances.</li>
 *   <li><b>delete(tenantId, agentId, serviceId)</b> — REQ-2026-006 new
 *       overload: {@code DELETE WHERE tenant_id AND agent_id AND service_id};
 *       deletes a single instance (rolling deploy of one replica).</li>
 *   <li><b>scanDueForProbe</b> — {@code SELECT tenant_id, agent_id,
 *       service_id, endpoint_url ... WHERE status IN ('ONLINE','DEGRADED')
 *       AND last_heartbeat < :staleBefore ORDER BY last_heartbeat ASC LIMIT
 *       :limit}. HD3-004 lease/TTL scan path. REQ-2026-006: returns
 *       {@code service_id} so the scheduler can call
 *       {@link #updateStatus(String, String, String, String, boolean)} with
 *       the right instance scope.</li>
 *   <li><b>updateStatus</b> — {@code UPDATE ... SET status = :newStatus [,
 *       last_heartbeat = NOW()] WHERE tenant_id AND agent_id AND service_id};
 *       REQ-2026-006: {@code service_id} added — health-probe results scoped
 *       per instance, not per agentId.</li>
 *   <li><b>listByAgentId</b> — REQ-2026-006 replaces
 *       {@code searchByAgentId}. {@code SELECT ... WHERE tenant_id AND
 *       agent_id AND status IN ('ONLINE','DEGRADED') ORDER BY weight DESC,
 *       last_heartbeat DESC}. Returns {@code List<RegistryRow>} (empty list
 *       = agent_not_found).</li>
 *   <li><b>findEndpoint</b> — {@code SELECT endpoint_url, route_key,
 *       contract_version WHERE tenant_id AND agent_id AND service_id};
 *       REQ-2026-006: {@code service_id} added — the codec decodes
 *       {@code serviceId} from the v1: 5-field handle and passes the triple.</li>
 * </ul>
 *
 * <h2>Stage 24 RLS wiring</h2>
 * Every method runs inside a short transaction that sets the
 * transaction-scoped {@code app.tenant_id} (via
 * {@code set_config('app.tenant_id', :tenantId, true)}, equivalent to
 * {@code SET LOCAL}) before the business SQL, so a restricted (non-owner)
 * connection is filtered by tenant. The setting and the transaction
 * auto-reset on return — no connection-pool pollution. Application-layer
 * {@code WHERE tenant_id = :tenantId} remains the primary isolation
 * (Rule R-C.c); RLS is the defence-in-depth fallback. The table owner
 * (superuser) bypasses RLS, so superuser-backed integration tests are
 * unaffected.
 */
public final class JdbcAgentRegistryRepository implements AgentRegistryRepository {

    private static final String TABLE = "agent_registry_mvp";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    /**
     * Backwards-compatible constructor: derives a per-DataSource
     * {@link DataSourceTransactionManager} so every method runs inside a
     * short transaction that sets {@code app.tenant_id} (Stage 24 RLS wiring).
     */
    public JdbcAgentRegistryRepository(DataSource dataSource) {
        this(dataSource, new DataSourceTransactionManager(dataSource));
    }

    /**
     * Full constructor: accepts an explicit {@link PlatformTransactionManager}
     * so production can supply a pooled / XA-aware manager, and tests can
     * inject a controlled one. The manager must bind connections from the
     * same {@link DataSource} so the transactional connection is the one
     * {@code app.tenant_id} is set on.
     */
    public JdbcAgentRegistryRepository(DataSource dataSource, PlatformTransactionManager txManager) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        Objects.requireNonNull(txManager, "txManager is required");
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(txManager);
    }

    // ===== upsert / delete / scan / update =====

    @Override
    public void upsert(AgentRegistryEntry entry, String a2aAgentCardJson) {
        Objects.requireNonNull(entry, "entry is required");
        requireNonBlank(entry.getTenantId(), "tenantId");
        requireNonBlank(entry.getAgentId(), "agentId");
        requireNonBlank(entry.getServiceId(), "serviceId");
        Objects.requireNonNull(entry.getFrameworkType(), "frameworkType is required");
        String sql = "INSERT INTO " + TABLE + " ("
                + "tenant_id, agent_id, service_id, agent_name, framework_type, "
                + "route_key, contract_version, "
                + "capability_version, endpoint_url, max_concurrency, weight, region, "
                + "a2a_agent_card, status, last_heartbeat) "
                + "VALUES (:tenantId, :agentId, :serviceId, :agentName, :frameworkType, "
                + ":routeKey, :contractVersion, "
                + ":capabilityVersion, :endpointUrl, :maxConcurrency, :weight, :region, "
                + ":a2aAgentCard::jsonb, 'ONLINE', CURRENT_TIMESTAMP) "
                + "ON CONFLICT (tenant_id, agent_id, service_id) DO UPDATE SET "
                + "agent_name = EXCLUDED.agent_name, "
                + "framework_type = EXCLUDED.framework_type, "
                + "route_key = EXCLUDED.route_key, "
                + "contract_version = EXCLUDED.contract_version, "
                + "capability_version = EXCLUDED.capability_version, "
                + "endpoint_url = EXCLUDED.endpoint_url, "
                + "max_concurrency = EXCLUDED.max_concurrency, "
                + "weight = EXCLUDED.weight, "
                + "region = EXCLUDED.region, "
                + "a2a_agent_card = EXCLUDED.a2a_agent_card, "
                // PR #389 #7: preserve DRAINING (operator-initiated graceful
                // drain) across re-registration; an agent restart must not
                // pull a draining entry back to ONLINE and re-route traffic
                // to it. ONLINE / DEGRADED / OFFLINE all reset to ONLINE
                // (agent restart semantics).
                + "status = CASE WHEN agent_registry_mvp.status = 'DRAINING' "
                + "THEN 'DRAINING' ELSE 'ONLINE' END, "
                + "last_heartbeat = CURRENT_TIMESTAMP";
        withTenant(entry.getTenantId(), () -> {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", entry.getTenantId())
                    .addValue("agentId", entry.getAgentId())
                    .addValue("serviceId", entry.getServiceId())
                    .addValue("agentName", entry.getAgentName())
                    .addValue("frameworkType", entry.getFrameworkType().name())
                    .addValue("routeKey", entry.getRouteKey())
                    .addValue("contractVersion", entry.getContractVersion())
                    .addValue("capabilityVersion", entry.getCapabilityVersion())
                    .addValue("endpointUrl", entry.getEndpointUrl())
                    .addValue("maxConcurrency", entry.getMaxConcurrency())
                    .addValue("weight", entry.getWeight())
                    .addValue("region", entry.getRegion())
                    .addValue("a2aAgentCard", a2aAgentCardJson);
            jdbc.update(sql, params);
            return null;
        });
    }

    @Override
    public boolean delete(String tenantId, String agentId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        return withTenant(tenantId, () -> {
            // REQ-2026-006: semantic generalization — delete ALL instances
            // for the (tenantId, agentId) pair. Previously deleted a single
            // row when PK was (tenant_id, agent_id); now deletes every
            // instance row matching the pair.
            String sql = "DELETE FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId);
            return jdbc.update(sql, params) > 0;
        });
    }

    @Override
    public boolean delete(String tenantId, String agentId, String serviceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        return withTenant(tenantId, () -> {
            String sql = "DELETE FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND service_id = :serviceId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("serviceId", serviceId);
            return jdbc.update(sql, params) > 0;
        });
    }

    @Override
    public List<ProbeTarget> scanDueForProbe(long staleBeforeMillis, int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0");
        }
        // HD3-004 lease/TTL scan: ONLINE rows whose heartbeat is older than
        // the stale threshold (caller passes NOW() - probe-interval), PLUS
        // DEGRADED rows so a recovered agent can be re-probed and restored
        // to ONLINE (PR #389 review issue #4). Pull-based registration entries
        // (inserted by PullRegistrationBootstrap) are scanned here the same
        // as push-based ones — they share the same table and lifecycle.
        // REQ-2026-006: SELECT service_id so the scheduler can call
        // updateStatus with the right instance scope.
        String sql = "SELECT tenant_id, agent_id, service_id, endpoint_url FROM " + TABLE
                + " WHERE status IN ('ONLINE','DEGRADED') AND last_heartbeat < :staleBefore"
                + " ORDER BY last_heartbeat ASC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("staleBefore", new java.sql.Timestamp(staleBeforeMillis))
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> new ProbeTarget(
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getString("service_id"),
                rs.getString("endpoint_url")));
    }

    @Override
    public boolean updateStatus(String tenantId, String agentId, String serviceId,
                                String newStatus, boolean refreshHeartbeat) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        requireNonBlank(newStatus, "newStatus");
        return withTenant(tenantId, () -> {
            String setClause = refreshHeartbeat
                    ? "status = :newStatus, last_heartbeat = CURRENT_TIMESTAMP"
                    : "status = :newStatus";
            // REQ-2026-006: WHERE includes service_id — health-probe results
            // scoped per instance, not per agentId.
            String sql = "UPDATE " + TABLE + " SET " + setClause
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND service_id = :serviceId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("newStatus", newStatus)
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("serviceId", serviceId);
            return jdbc.update(sql, params) > 0;
        });
    }

    // ===== discovery (list lookup, REQ-2026-006) =====

    @Override
    public List<RegistryRow> listByAgentId(String tenantId, String agentId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        return withTenant(tenantId, () -> {
            String sql = "SELECT service_id, agent_id, agent_name, framework_type, "
                    + "route_key, contract_version, capability_version, "
                    + "weight, region, max_concurrency, status FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND status IN ('ONLINE','DEGRADED')"
                    + " ORDER BY weight DESC, last_heartbeat DESC";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId);
            return jdbc.query(sql, params, RegistryRowMapper.INSTANCE);
        });
    }

    @Override
    public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId, String serviceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        return withTenant(tenantId, () -> {
            String sql = "SELECT endpoint_url, route_key, contract_version FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND service_id = :serviceId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("serviceId", serviceId);
            List<EndpointEntry> rows = jdbc.query(sql, params, (rs, rowNum) -> new EndpointEntry(
                    rs.getString("endpoint_url"),
                    rs.getString("route_key"),
                    rs.getString("contract_version")));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        });
    }

    // ===== internals =====

    /**
     * Stage 24 RLS wiring — see class javadoc.
     */
    private <T> T withTenant(String tenantId, Supplier<T> work) {
        return txTemplate.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', :tenantId, true)",
                    new MapSqlParameterSource("tenantId", tenantId), String.class);
            return work.get();
        });
    }

    /** Row mapper for {@link RegistryRow} — single instance, stateless. */
    private static final class RegistryRowMapper
            implements org.springframework.jdbc.core.RowMapper<RegistryRow> {
        static final RegistryRowMapper INSTANCE = new RegistryRowMapper();

        @Override
        public RegistryRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            String frameworkTypeName = rs.getString("framework_type");
            FrameworkType frameworkType = frameworkTypeName == null
                    ? null : FrameworkType.valueOf(frameworkTypeName);
            return new RegistryRow(
                    rs.getString("service_id"),
                    rs.getString("agent_id"),
                    rs.getString("agent_name"),
                    frameworkType,
                    rs.getString("route_key"),
                    rs.getString("contract_version"),
                    rs.getString("capability_version"),
                    rs.getInt("weight"),
                    rs.getString("region"),
                    rs.getInt("max_concurrency"),
                    rs.getString("status"));
        }
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
