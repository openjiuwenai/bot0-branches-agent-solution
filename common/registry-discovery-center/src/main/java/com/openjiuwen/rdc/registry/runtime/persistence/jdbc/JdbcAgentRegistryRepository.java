/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime.persistence.jdbc;

import com.openjiuwen.rdc.model.AgentRegistryEntry;
import com.openjiuwen.rdc.model.FrameworkType;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import javax.sql.DataSource;

/**
 * Postgres JDBC adapter for {@code agent_registry_mvp} (Stage 4 / ADR-0160
 * decision 4). The only class in {@code com.openjiuwen.rdc.registry.**}
 * allowed to import {@code java.sql} / {@code javax.sql} /
 * {@code org.springframework.jdbc.*} — enforced at test time by
 * {@code AgentRdcRegistryJdbcPurityTest} (S5).
 *
 * <h2>SQL contract (FEAT-016 revised)</h2>
 * <ul>
 *   <li><b>upsert</b> — {@code INSERT ... ON CONFLICT (tenant_id, agent_id,
 *       service_id, instance_id) DO UPDATE SET ...} ; an agent restart
 *       overwrites the prior entry and resets {@code status = 'ONLINE'} +
 *       {@code last_heartbeat = NOW()}, EXCEPT when the prior status is
 *       {@code DRAINING} (operator-initiated graceful drain) — DRAINING is
 *       preserved so a restart during drain does not re-route traffic to
 *       the agent (PR #389 review issue #7). FEAT-016: {@code instance_id}
 *       is the fourth PK column (server-derived from {@code endpoint_url}
 *       via {@code InstanceIdCodec}); {@code capabilities} VARCHAR(64)[] is
 *       written from {@code entry.getCapabilities()} (empty array when null);
 *       {@code ON CONFLICT} scopes to concrete-instance level so N instances
 *       of the same {@code agentId} + {@code serviceId} upsert independently.</li>
 *   <li><b>delete(tenantId, agentId)</b> — {@code DELETE WHERE tenant_id =
 *       :tenantId AND agent_id = :agentId}; deletes ALL instances for the pair.</li>
 *   <li><b>delete(tenantId, agentId, serviceId)</b> — {@code DELETE WHERE
 *       tenant_id AND agent_id AND service_id}; deletes ALL concrete
 *       instances under the triple (was single-instance in REQ-2026-006).</li>
 *   <li><b>delete(tenantId, agentId, serviceId, instanceId)</b> — FEAT-016
 *       new: {@code DELETE WHERE tenant_id AND agent_id AND service_id AND
 *       instance_id}; deletes a single concrete instance (rolling deploy of
 *       one replica).</li>
 *   <li><b>scanDueForProbe</b> — {@code SELECT tenant_id, agent_id,
 *       service_id, instance_id, endpoint_url ... WHERE status IN
 *       ('ONLINE','DEGRADED') AND last_heartbeat < :staleBefore ORDER BY
 *       last_heartbeat ASC LIMIT :limit}. HD3-004 lease/TTL scan path.
 *       FEAT-016: returns {@code instance_id} so the scheduler can call
 *       {@link #updateStatus(StatusUpdate)}
 *       with the right concrete-instance scope.</li>
 *   <li><b>updateStatus</b> — {@code UPDATE ... SET status = :newStatus [,
 *       last_heartbeat = NOW()] WHERE tenant_id AND agent_id AND service_id
 *       AND instance_id}; FEAT-016: {@code instance_id} added — health-probe
 *       results scoped per concrete instance.</li>
 *   <li><b>listByAgentId</b> — {@code SELECT ... WHERE tenant_id AND
 *       agent_id AND status IN ('ONLINE','DEGRADED','DRAINING') AND
 *       (:contractVersion IS NULL OR contract_version = :contractVersion)
 *       ORDER BY weight DESC, last_heartbeat DESC}. FEAT-016: DRAINING now
 *       included (was excluded in REQ-2026-006); nullable contractVersion
 *       filter added; SELECT reads {@code instance_id} + {@code capabilities}.</li>
 *   <li><b>listByServiceId</b> — FEAT-016 new: same SELECT/sort as
 *       listByAgentId, WHERE on {@code tenant_id AND service_id}.</li>
 *   <li><b>listByCapability</b> — FEAT-016 new: same SELECT/sort, WHERE on
 *       {@code tenant_id AND capabilities @> ARRAY[:capability]::varchar[]}
 *       (uses {@code @>} containment so the GIN index on {@code capabilities}
 *       accelerates the lookup; {@code = ANY()} would fall back to seq scan).</li>
 *   <li><b>findEndpoint</b> — {@code SELECT endpoint_url, route_key,
 *       contract_version WHERE tenant_id AND agent_id AND service_id AND
 *       instance_id}; FEAT-016: {@code instance_id} added — the codec
 *       decodes {@code instanceId} from the v2: 6-field handle and passes
 *       the 4-field PK.</li>
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
 * @since 2026-07-10
 */
public final class JdbcAgentRegistryRepository implements AgentRegistryRepository {
    private static final String TABLE = "agent_registry_mvp";

    private static final String UPSERT_SQL = "INSERT INTO " + TABLE + " ("
            + "tenant_id, agent_id, service_id, instance_id, agent_name, framework_type, "
            + "route_key, contract_version, "
            + "capability_version, endpoint_url, max_concurrency, weight, region, "
            + "a2a_agent_card, capabilities, status, last_heartbeat) "
            + "VALUES (:tenantId, :agentId, :serviceId, :instanceId, :agentName, :frameworkType, "
            + ":routeKey, :contractVersion, "
            + ":capabilityVersion, :endpointUrl, :maxConcurrency, :weight, :region, "
            + ":a2aAgentCard::jsonb, :capabilities, 'ONLINE', CURRENT_TIMESTAMP) "
            + "ON CONFLICT (tenant_id, agent_id, service_id, instance_id) DO UPDATE SET "
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
            + "capabilities = EXCLUDED.capabilities, "
            // PR #389 #7: preserve DRAINING (operator-initiated graceful
            // drain) across re-registration; an agent restart must not
            // pull a draining entry back to ONLINE and re-route traffic
            // to it. ONLINE / DEGRADED / OFFLINE all reset to ONLINE
            // (agent restart semantics).
            + "status = CASE WHEN agent_registry_mvp.status = 'DRAINING' "
            + "THEN 'DRAINING' ELSE 'ONLINE' END, "
            + "last_heartbeat = CURRENT_TIMESTAMP";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    /**
     * Backwards-compatible constructor: derives a per-DataSource
     * {@link DataSourceTransactionManager} so every method runs inside a
     * short transaction that sets {@code app.tenant_id} (Stage 24 RLS wiring).
     *
     * @param dataSource the registry DataSource (must supply non-superuser
     *                   connections for RLS to take effect)
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
     *
     * @param dataSource the registry DataSource
     * @param txManager  the transaction manager binding connections from
     *                   {@code dataSource} (so the RLS set_config runs on the
     *                   same connection as the business SQL)
     */
    public JdbcAgentRegistryRepository(DataSource dataSource, PlatformTransactionManager txManager) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        Objects.requireNonNull(txManager, "txManager is required");
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @Override
    public void upsert(AgentRegistryEntry entry, String a2aAgentCardJson) {
        Objects.requireNonNull(entry, "entry is required");
        requireNonBlank(entry.getTenantId(), "tenantId");
        requireNonBlank(entry.getAgentId(), "agentId");
        requireNonBlank(entry.getServiceId(), "serviceId");
        requireNonBlank(entry.getInstanceId(), "instanceId");
        Objects.requireNonNull(entry.getFrameworkType(), "frameworkType is required");
        withTenant(entry.getTenantId(), () -> {
            List<String> capabilities = entry.getCapabilities() != null
                    ? entry.getCapabilities() : List.of();
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", entry.getTenantId())
                    .addValue("agentId", entry.getAgentId())
                    .addValue("serviceId", entry.getServiceId())
                    .addValue("instanceId", entry.getInstanceId())
                    .addValue("agentName", entry.getAgentName())
                    .addValue("frameworkType", entry.getFrameworkType().name())
                    .addValue("routeKey", entry.getRouteKey())
                    .addValue("contractVersion", entry.getContractVersion())
                    .addValue("capabilityVersion", entry.getCapabilityVersion())
                    .addValue("endpointUrl", entry.getEndpointUrl())
                    .addValue("maxConcurrency", entry.getMaxConcurrency())
                    .addValue("weight", entry.getWeight())
                    .addValue("region", entry.getRegion())
                    .addValue("a2aAgentCard", a2aAgentCardJson)
                    .addValue("capabilities", capabilities.toArray(new String[0]));
            jdbc.update(UPSERT_SQL, params);
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
    public boolean delete(String tenantId, String agentId, String serviceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        return withTenant(tenantId, () -> {
            // FEAT-016: deletes ALL concrete instances under the triple
            // (was single-instance in REQ-2026-006 when PK was 3-field).
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
    public boolean delete(String tenantId, String agentId, String serviceId, String instanceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        requireNonBlank(instanceId, "instanceId");
        return withTenant(tenantId, () -> {
            // FEAT-016 new: delete a single concrete instance by 4-field PK.
            String sql = "DELETE FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND service_id = :serviceId AND instance_id = :instanceId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("serviceId", serviceId)
                    .addValue("instanceId", instanceId);
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
        // FEAT-016: SELECT instance_id so the scheduler can call
        // updateStatus with the right concrete-instance scope.
        String sql = "SELECT tenant_id, agent_id, service_id, instance_id, endpoint_url FROM " + TABLE
                + " WHERE status IN ('ONLINE','DEGRADED') AND last_heartbeat < :staleBefore"
                + " ORDER BY last_heartbeat ASC LIMIT :limit";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("staleBefore", new java.sql.Timestamp(staleBeforeMillis))
                .addValue("limit", limit);
        return jdbc.query(sql, params, (rs, rowNum) -> new ProbeTarget(
                rs.getString("tenant_id"),
                rs.getString("agent_id"),
                rs.getString("service_id"),
                rs.getString("instance_id"),
                rs.getString("endpoint_url")));
    }

    @Override
    public boolean updateStatus(AgentRegistryRepository.StatusUpdate update) {
        requireNonBlank(update.tenantId(), "tenantId");
        requireNonBlank(update.agentId(), "agentId");
        requireNonBlank(update.serviceId(), "serviceId");
        requireNonBlank(update.instanceId(), "instanceId");
        requireNonBlank(update.newStatus(), "newStatus");
        return withTenant(update.tenantId(), () -> {
            String setClause = update.shouldRefreshHeartbeat()
                    ? "status = :newStatus, last_heartbeat = CURRENT_TIMESTAMP"
                    : "status = :newStatus";
            // FEAT-016: WHERE includes instance_id — health-probe results
            // scoped per concrete instance, not per serviceId.
            String sql = "UPDATE " + TABLE + " SET " + setClause
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND service_id = :serviceId AND instance_id = :instanceId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("newStatus", update.newStatus())
                    .addValue("tenantId", update.tenantId())
                    .addValue("agentId", update.agentId())
                    .addValue("serviceId", update.serviceId())
                    .addValue("instanceId", update.instanceId());
            return jdbc.update(sql, params) > 0;
        });
    }

    @Override
    public List<RegistryRow> listByAgentId(String tenantId, String agentId, String contractVersion) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        return withTenant(tenantId, () -> {
            String sql = "SELECT service_id, instance_id, agent_id, agent_name, framework_type, "
                    + "route_key, contract_version, capability_version, "
                    + "weight, region, max_concurrency, status, capabilities FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    // FEAT-016: DRAINING now included (was excluded in
                    // REQ-2026-006). The caller sees DRAINING as a
                    // limited-availability health state.
                    + " AND status IN ('ONLINE','DEGRADED','DRAINING')"
                    // FEAT-016: nullable contractVersion filter. The CAST
                    // gives PostgreSQL a type hint for the null case —
                    // without it the driver raises "could not determine data
                    // type of parameter" when contractVersion is null.
                    + " AND (CAST(:contractVersion AS varchar) IS NULL OR contract_version = :contractVersion)"
                    + " ORDER BY weight DESC, last_heartbeat DESC";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("contractVersion", contractVersion);
            return jdbc.query(sql, params, RegistryRowMapper.INSTANCE);
        });
    }

    @Override
    public List<RegistryRow> listByServiceId(String tenantId, String serviceId, String contractVersion) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(serviceId, "serviceId");
        return withTenant(tenantId, () -> {
            String sql = "SELECT service_id, instance_id, agent_id, agent_name, framework_type, "
                    + "route_key, contract_version, capability_version, "
                    + "weight, region, max_concurrency, status, capabilities FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND service_id = :serviceId"
                    + " AND status IN ('ONLINE','DEGRADED','DRAINING')"
                    + " AND (CAST(:contractVersion AS varchar) IS NULL OR contract_version = :contractVersion)"
                    + " ORDER BY weight DESC, last_heartbeat DESC";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("serviceId", serviceId)
                    .addValue("contractVersion", contractVersion);
            return jdbc.query(sql, params, RegistryRowMapper.INSTANCE);
        });
    }

    @Override
    public List<RegistryRow> listByCapability(String tenantId, String capability, String contractVersion) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(capability, "capability");
        return withTenant(tenantId, () -> {
            String sql = "SELECT service_id, instance_id, agent_id, agent_name, framework_type, "
                    + "route_key, contract_version, capability_version, "
                    + "weight, region, max_concurrency, status, capabilities FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND capabilities @> ARRAY[:capability]::varchar[]"
                    + " AND status IN ('ONLINE','DEGRADED','DRAINING')"
                    + " AND (CAST(:contractVersion AS varchar) IS NULL OR contract_version = :contractVersion)"
                    + " ORDER BY weight DESC, last_heartbeat DESC";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("capability", capability)
                    .addValue("contractVersion", contractVersion);
            return jdbc.query(sql, params, RegistryRowMapper.INSTANCE);
        });
    }

    @Override
    public Optional<EndpointEntry> findEndpoint(String tenantId, String agentId,
                                                String serviceId, String instanceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        requireNonBlank(instanceId, "instanceId");
        return withTenant(tenantId, () -> {
            String sql = "SELECT endpoint_url, route_key, contract_version FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND service_id = :serviceId AND instance_id = :instanceId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("serviceId", serviceId)
                    .addValue("instanceId", instanceId);
            List<EndpointEntry> rows = jdbc.query(sql, params, (rs, rowNum) -> new EndpointEntry(
                    rs.getString("endpoint_url"),
                    rs.getString("route_key"),
                    rs.getString("contract_version")));
            return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
        });
    }

    /**
     * Stage 24 RLS wiring — see class javadoc. Runs {@code work} inside a
     * short transaction that first sets {@code app.tenant_id} so a
     * restricted (non-owner) connection is filtered by tenant.
     *
     * @param tenantId the tenant id to bind on the transactional connection
     * @param work     the business SQL to run with the tenant context set
     * @param <T>      the result type
     * @return the value produced by {@code work}
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
            // FEAT-016: read instance_id (2nd field) + capabilities (array).
            // rs.getArray returns java.sql.Array; .getArray() yields String[].
            // Null capabilities column → empty list.
            List<String> capabilities = readCapabilities(rs);
            return new RegistryRow(
                    rs.getString("service_id"),
                    rs.getString("instance_id"),
                    rs.getString("agent_id"),
                    rs.getString("agent_name"),
                    frameworkType,
                    rs.getString("route_key"),
                    rs.getString("contract_version"),
                    rs.getString("capability_version"),
                    rs.getInt("weight"),
                    rs.getString("region"),
                    rs.getInt("max_concurrency"),
                    rs.getString("status"),
                    capabilities);
        }

        private static List<String> readCapabilities(java.sql.ResultSet rs) throws SQLException {
            Array arr = rs.getArray("capabilities");
            if (arr == null) {
                return List.of();
            }
            Object raw = arr.getArray();
            if (raw instanceof String[]) {
                String[] caps = (String[]) raw;
                return caps.length == 0 ? List.of() : List.copyOf(Arrays.asList(caps));
            }
            return List.of();
        }
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
