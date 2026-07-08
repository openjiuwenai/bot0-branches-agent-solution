package com.openjiuwen.rdc.registry.runtime.persistence.jdbc;

import com.openjiuwen.rdc.spi.registry.AgentCard;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Postgres JDBC adapter for {@code agent_registry_mvp} (Stage 4 / ADR-0160
 * decision 4). The only class in {@code com.openjiuwen.rdc.registry.**}
 * allowed to import {@code java.sql} / {@code javax.sql} /
 * {@code org.springframework.jdbc.*} — enforced at test time by
 * {@code AgentRdcRegistryJdbcPurityTest} (S5).
 *
 * <h2>SQL contract</h2>
 * <ul>
 *   <li><b>upsert</b> — {@code INSERT ... ON CONFLICT (tenant_id, agent_id)
 *       DO UPDATE SET ...} ; an agent restart overwrites the prior entry and
 *       resets {@code status = 'ONLINE'} + {@code last_heartbeat = NOW()},
 *       EXCEPT when the prior status is {@code DRAINING} (operator-initiated
 *       graceful drain) — DRAINING is preserved so a restart during drain
 *       does not re-route traffic to the agent (PR #389 review issue #7).</li>
 *   <li><b>delete</b> — {@code DELETE WHERE tenant_id = :tenantId AND
 *       agent_id = :agentId}; returns affected-row count &gt; 0.</li>
 *   <li><b>scanDueForProbe</b> — {@code SELECT ... WHERE status IN ('ONLINE','DEGRADED')
 *       AND last_heartbeat < :staleBefore ORDER BY last_heartbeat ASC LIMIT :limit}.
 *       HD3-004 lease/TTL scan path. DEGRADED rows are included so a recovered
 *       agent can be re-probed and restored to ONLINE (PR #389 review issue #4).
 *       Runs unscoped (no {@code withTenant} wrap) — REQUIRES an owner-role
 *       connection; under a restricted role the RLS policy filters everything
 *       (PR #389 review issue #3, see Pr389RlsAndRecoveryFeedbackLoopTest).</li>
 *   <li><b>updateStatus</b> — {@code UPDATE ... SET status = :newStatus [,
 *       last_heartbeat = NOW()] WHERE tenant_id AND agent_id}; the scheduler
 *       uses {@code refreshHeartbeat=false} when downgrading (5xx → DEGRADED)
 *       and {@code true} when reaffirming (200 → ONLINE).</li>
 *   <li><b>searchByIntent / searchByCapability</b> — both filter by
 *       {@code status IN ('ONLINE','DEGRADED')} AND
 *       {@code last_heartbeat >= NOW() - INTERVAL '15 seconds'} (HD3-004
 *       visibility window) AND optional {@code contract_version} exact match
 *       AND optional {@code search_tsv @@ websearch_to_tsquery} ranking;
 *       ordered by ts_rank DESC, weight DESC when {@code userQuery} is
 *       non-null, else weight DESC only. The {@code status IN ('ONLINE','DEGRADED')}
 *       divergence from the design doc's {@code WHERE status = 'ONLINE'} is
 *       per PRD FR-4/FR-5 (HD3-004 alignment: DEGRADED targets stay
 *       discoverable but marked). PR #389 review issue #9: switched from
 *       {@code phraseto_tsquery} (requires token adjacency) to
 *       {@code websearch_to_tsquery} (keyword-style) to match the L2 design
 *       §3.3.1 "关键词分词检索" intent.</li>
 *   <li><b>findEndpoint</b> — {@code SELECT endpoint_url, route_key,
 *       contract_version WHERE tenant_id AND agent_id}; used by
 *       {@code AgentDiscoveryService.resolveRouteHandle} after the codec has
 *       decoded the opaque handle. Returns {@code Optional#empty()} when no
 *       row matches → mapped to {@code entry_not_found} (HTTP 404) by the
 *       caller.</li>
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
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * registry-discovery-runtime-design.cn.md} §3.2 / §3.3.1 (port/adapter
 * refactoring per ADR-0160 decision 4 — JdbcTemplate usage下沉 from
 * {@code PgMvpDiscoveryServiceImpl} to this adapter);
 * {@code ICD-Agent-Registry-Discovery} HD3-001/003/004/005/006.
 */
public final class JdbcAgentRegistryRepository implements AgentRegistryRepository {

    private static final String TABLE = "agent_registry_mvp";
    private static final String VISIBILITY_WINDOW = "15 seconds";

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
    public void upsert(AgentCard card) {
        Objects.requireNonNull(card, "card is required");
        requireNonBlank(card.getTenantId(), "tenantId");
        requireNonBlank(card.getAgentId(), "agentId");
        String sql = "INSERT INTO " + TABLE + " ("
                + "tenant_id, agent_id, service_id, agent_name, agent_type, capability, "
                + "capability_keywords, system_profile, route_key, contract_version, "
                + "capability_version, endpoint_url, max_concurrency, weight, region, "
                + "tool_schemas, status, last_heartbeat) "
                + "VALUES (:tenantId, :agentId, :serviceId, :agentName, :agentType, :capability, "
                + ":capabilityKeywords, :systemProfile, :routeKey, :contractVersion, "
                + ":capabilityVersion, :endpointUrl, :maxConcurrency, :weight, :region, "
                + ":toolSchemas::jsonb, 'ONLINE', CURRENT_TIMESTAMP) "
                + "ON CONFLICT (tenant_id, agent_id) DO UPDATE SET "
                + "service_id = EXCLUDED.service_id, "
                + "agent_name = EXCLUDED.agent_name, "
                + "agent_type = EXCLUDED.agent_type, "
                + "capability = EXCLUDED.capability, "
                + "capability_keywords = EXCLUDED.capability_keywords, "
                + "system_profile = EXCLUDED.system_profile, "
                + "route_key = EXCLUDED.route_key, "
                + "contract_version = EXCLUDED.contract_version, "
                + "capability_version = EXCLUDED.capability_version, "
                + "endpoint_url = EXCLUDED.endpoint_url, "
                + "max_concurrency = EXCLUDED.max_concurrency, "
                + "weight = EXCLUDED.weight, "
                + "region = EXCLUDED.region, "
                + "tool_schemas = EXCLUDED.tool_schemas, "
                // PR #389 #7: preserve DRAINING (operator-initiated graceful
                // drain) across re-registration; an agent restart must not
                // pull a draining entry back to ONLINE and re-route traffic
                // to it. ONLINE / DEGRADED / OFFLINE all reset to ONLINE
                // (agent restart semantics).
                + "status = CASE WHEN agent_registry_mvp.status = 'DRAINING' "
                + "THEN 'DRAINING' ELSE 'ONLINE' END, "
                + "last_heartbeat = CURRENT_TIMESTAMP";
        withTenant(card.getTenantId(), () -> {
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", card.getTenantId())
                    .addValue("agentId", card.getAgentId())
                    .addValue("serviceId", card.getServiceId())
                    .addValue("agentName", card.getAgentName())
                    .addValue("agentType", card.getAgentType())
                    .addValue("capability", card.getCapability())
                    .addValue("capabilityKeywords", card.getCapabilityKeywords())
                    .addValue("systemProfile", card.getSystemProfile())
                    .addValue("routeKey", card.getRouteKey())
                    .addValue("contractVersion", card.getContractVersion())
                    .addValue("capabilityVersion", card.getCapabilityVersion())
                    .addValue("endpointUrl", card.getEndpointUrl())
                    .addValue("maxConcurrency", card.getMaxConcurrency())
                    .addValue("weight", card.getWeight())
                    .addValue("region", card.getRegion())
                    .addValue("toolSchemas", card.getToolSchemas());
            jdbc.update(sql, params);
            return null;
        });
    }

    @Override
    public boolean delete(String tenantId, String agentId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        return withTenant(tenantId, () -> {
            String sql = "DELETE FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId);
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
        // to ONLINE (PR #389 review issue #4 — without this, DEGRADED is an
        // unrecoverable terminal state until the 15-second visibility window
        // evicts the row or a fresh upsert re-registers it; flappy networks
        // make that pathological). The scheduler scopes by tenant via
        // withTenant before issuing this query — but the scan is
        // tenant-agnostic here so the scheduler can sweep all tenants in one
        // call. To respect Stage 24 RLS wiring per-tenant, the scheduler
        // wraps each row's downstream probe in its own tenant transaction;
        // this scan itself runs unscoped and REQUIRES an owner-role
        // connection (RLS bypassed by owner). Under a restricted role
        // (app_role_rls), the scan returns empty because the adapter never
        // sets app.tenant_id for this call — see
        // Pr389RlsAndRecoveryFeedbackLoopTest for the contract test that
        // documents this deployment requirement.
        String sql = "SELECT tenant_id, agent_id, endpoint_url FROM " + TABLE
                + " WHERE status IN ('ONLINE','DEGRADED') AND last_heartbeat < :staleBefore"
                + " ORDER BY last_heartbeat ASC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("staleBefore", new java.sql.Timestamp(staleBeforeMillis))
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> new ProbeTarget(
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getString("endpoint_url")));
    }

    @Override
    public boolean updateStatus(String tenantId, String agentId, String newStatus, boolean refreshHeartbeat) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(newStatus, "newStatus");
        return withTenant(tenantId, () -> {
            String setClause = refreshHeartbeat
                    ? "status = :newStatus, last_heartbeat = CURRENT_TIMESTAMP"
                    : "status = :newStatus";
            String sql = "UPDATE " + TABLE + " SET " + setClause
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("newStatus", newStatus)
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId);
            return jdbc.update(sql, params) > 0;
        });
    }

    // ===== discovery search (Method A / Method B) =====

    @Override
    public List<RegistryRow> searchByIntent(String tenantId, String userQuery,
                                             String contractVersion, int topK) {
        requireNonBlank(tenantId, "tenantId");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }
        return withTenant(tenantId, () -> {
            StringBuilder sql = new StringBuilder()
                    .append("SELECT agent_id, service_id, agent_name, agent_type, capability, ")
                    .append("route_key, contract_version, capability_version, system_profile, ")
                    .append("tool_schemas, weight, region, status FROM ").append(TABLE)
                    .append(" WHERE tenant_id = :tenantId")
                    .append(" AND status IN ('ONLINE','DEGRADED')")
                    .append(" AND last_heartbeat >= NOW() - INTERVAL '").append(VISIBILITY_WINDOW).append("'");
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("topK", topK);
            appendVersionAndRankFilters(sql, params, contractVersion, userQuery);
            sql.append(" LIMIT :topK");
            return jdbc.query(sql.toString(), params, RegistryRowMapper.INSTANCE);
        });
    }

    @Override
    public List<RegistryRow> searchByCapability(String tenantId, String capability, String userQuery,
                                                 String contractVersion, int topK) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(capability, "capability");
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0");
        }
        return withTenant(tenantId, () -> {
            StringBuilder sql = new StringBuilder()
                    .append("SELECT agent_id, service_id, agent_name, agent_type, capability, ")
                    .append("route_key, contract_version, capability_version, system_profile, ")
                    .append("tool_schemas, weight, region, status FROM ").append(TABLE)
                    .append(" WHERE tenant_id = :tenantId")
                    .append(" AND capability = :capability")
                    .append(" AND status IN ('ONLINE','DEGRADED')")
                    .append(" AND last_heartbeat >= NOW() - INTERVAL '").append(VISIBILITY_WINDOW).append("'");
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("capability", capability)
                    .addValue("topK", topK);
            appendVersionAndRankFilters(sql, params, contractVersion, userQuery);
            sql.append(" LIMIT :topK");
            return jdbc.query(sql.toString(), params, RegistryRowMapper.INSTANCE);
        });
    }

    @Override
    public java.util.Optional<EndpointEntry> findEndpoint(String tenantId, String agentId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        return withTenant(tenantId, () -> {
            String sql = "SELECT endpoint_url, route_key, contract_version FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId);
            List<EndpointEntry> rows = jdbc.query(sql, params, (rs, rowNum) -> new EndpointEntry(
                    rs.getString("endpoint_url"),
                    rs.getString("route_key"),
                    rs.getString("contract_version")));
            return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
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

    /**
     * Append the optional {@code contract_version = :contractVersion} filter
     * and the optional {@code search_tsv @@ websearch_to_tsquery} filter +
     * rank ordering to the discovery SQL builder. When {@code userQuery} is
     * non-null, ts_rank is computed and the ORDER BY ranks by relevance
     * first; when null, the ORDER BY is weight-only.
     *
     * <p>PR #389 review issue #9: switched from {@code phraseto_tsquery}
     * (requires token adjacency — phrase match) to {@code websearch_to_tsquery}
     * (keyword-style: tokens are OR'd, quoted phrases supported, no
     * adjacency requirement). The L2 design §3.3.1 specifies "关键词分词检索"
     * (keyword tokenization search) for stage 1, which {@code phraseto_tsquery}
     * did not implement — a query like "理财 产品" (different word order
     * from the indexed "产品 理财") returned 0 results under
     * {@code phraseto_tsquery} but matches under
     * {@code websearch_to_tsquery}.
     */
    private void appendVersionAndRankFilters(StringBuilder sql, MapSqlParameterSource params,
                                             String contractVersion, String userQuery) {
        if (contractVersion != null) {
            sql.append(" AND contract_version = :contractVersion");
            params.addValue("contractVersion", contractVersion);
        }
        if (userQuery != null) {
            sql.append(" AND search_tsv @@ websearch_to_tsquery('simple', :userQuery)");
            sql.append(" ORDER BY ts_rank(search_tsv, websearch_to_tsquery('simple', :userQuery)) DESC, weight DESC");
            params.addValue("userQuery", userQuery);
        } else {
            sql.append(" ORDER BY weight DESC");
        }
    }

    /** Row mapper for {@link RegistryRow} — single instance, stateless. */
    private static final class RegistryRowMapper
            implements org.springframework.jdbc.core.RowMapper<RegistryRow> {
        static final RegistryRowMapper INSTANCE = new RegistryRowMapper();

        @Override
        public RegistryRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            return new RegistryRow(
                    rs.getString("agent_id"),
                    rs.getString("service_id"),
                    rs.getString("agent_name"),
                    rs.getString("agent_type"),
                    rs.getString("capability"),
                    rs.getString("route_key"),
                    rs.getString("contract_version"),
                    rs.getString("capability_version"),
                    rs.getString("system_profile"),
                    rs.getString("tool_schemas"),
                    rs.getInt("weight"),
                    rs.getString("region"),
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
