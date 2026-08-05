/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import com.openjiuwen.rdc.card.CardDigest;
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
import java.util.function.Supplier;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

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
 *
 * @since 0.1.0
 */
public final class JdbcAgentRegistryRepository implements AgentRegistryRepository {
    private static final String TABLE = "agent_registry_mvp";
    private static final String REGISTRATION_TABLE = "agent_card_registration";
    private static final String SOURCE_REF_TABLE = "agent_card_source_ref";

    private static final String UPSERT_SQL = "INSERT INTO " + TABLE + " ("
            + "tenant_id, agent_id, service_id, instance_id, agent_name, framework_type, "
            + "route_key, contract_version, "
            + "capability_version, endpoint_url, max_concurrency, weight, region, "
            + "a2a_agent_card, capabilities, status, last_heartbeat, lifecycle_status, effective_health, "
            + "freshness, lease_expires_at, last_validated_at, source_id, source_revision) "
            + "VALUES (:tenantId, :agentId, :serviceId, :instanceId, :agentName, :frameworkType, "
            + ":routeKey, :contractVersion, "
            + ":capabilityVersion, :endpointUrl, :maxConcurrency, :weight, :region, "
            + ":a2aAgentCard::jsonb, :capabilities, 'ONLINE', CURRENT_TIMESTAMP, 'ACTIVE', 'HEALTHY', "
            + "'FRESH', CURRENT_TIMESTAMP + INTERVAL '1 hour', CURRENT_TIMESTAMP, "
            + "'legacy-push', 0) "
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
            + "status = CASE WHEN agent_registry_mvp.status = 'DRAINING' "
            + "THEN 'DRAINING' ELSE 'ONLINE' END, "
            + "lifecycle_status = CASE WHEN agent_registry_mvp.lifecycle_status = 'DRAINING' "
            + "THEN 'DRAINING' ELSE 'ACTIVE' END, "
            + "effective_health = CASE WHEN agent_registry_mvp.lifecycle_status = 'DRAINING' "
            + "THEN agent_registry_mvp.effective_health ELSE 'HEALTHY' END, "
            + "freshness = 'FRESH', "
            + "lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 hour', "
            + "last_validated_at = CURRENT_TIMESTAMP, "
            + "last_heartbeat = CURRENT_TIMESTAMP";

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate txTemplate;

    /**
     * Backwards-compatible constructor: derives a per-DataSource
     * {@link DataSourceTransactionManager} so every method runs inside a
     * short transaction that sets {@code app.tenant_id} (Stage 24 RLS wiring).
     *
     * @param dataSource dataSource
     * @return result
     * @since 0.1.0
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
     * @param dataSource dataSource
     * @param txManager txManager
     * @return result
     * @since 0.1.0
     */
    public JdbcAgentRegistryRepository(DataSource dataSource, PlatformTransactionManager txManager) {
        Objects.requireNonNull(dataSource, "dataSource is required");
        Objects.requireNonNull(txManager, "txManager is required");
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /**
     * upsert.
     *
     * @param entry entry
     * @param a2aAgentCardJson a2aAgentCardJson
     * @since 0.1.0
     */
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
            syncLogicalFromPushUpsert(entry, a2aAgentCardJson);
            return null;
        });
    }

    /**
     * delete.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @return result
     * @since 0.1.0
     */
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

    /**
     * delete.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @return result
     * @since 0.1.0
     */
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

    /**
     * delete.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @param instanceId instanceId
     * @return result
     * @since 0.1.0
     */
    @Override
    public boolean delete(String tenantId, String agentId, String serviceId, String instanceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        requireNonBlank(instanceId, "instanceId");
        return withTenant(tenantId, () -> {
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

    /**
     * scanDueForProbe.
     *
     * @param staleBeforeMillis staleBeforeMillis
     * @param limit limit
     * @return result
     * @since 0.1.0
     */
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

    /**
     * updateStatus.
     *
     * @param update update
     * @return result
     * @since 0.1.0
     */
    @Override
    public boolean updateStatus(StatusUpdate update) {
        requireNonBlank(update.tenantId(), "tenantId");
        requireNonBlank(update.agentId(), "agentId");
        requireNonBlank(update.serviceId(), "serviceId");
        requireNonBlank(update.instanceId(), "instanceId");
        requireNonBlank(update.newStatus(), "newStatus");
        return withTenant(update.tenantId(), () -> {
            String effectiveHealth = mapStatusToEffectiveHealth(update.newStatus());
            String lifecycle = mapStatusToLifecycle(update.newStatus());
            String setClause = update.shouldRefreshHeartbeat()
                    ? "status = :newStatus, lifecycle_status = :lifecycle, "
                    + "effective_health = :effectiveHealth, "
                    + "last_heartbeat = CURRENT_TIMESTAMP, "
                    + "lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 hour', "
                    + "last_validated_at = CURRENT_TIMESTAMP"
                    : "status = :newStatus, lifecycle_status = :lifecycle, "
                    + "effective_health = :effectiveHealth";
            String sql = "UPDATE " + TABLE + " SET " + setClause
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND service_id = :serviceId AND instance_id = :instanceId";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("newStatus", update.newStatus())
                    .addValue("lifecycle", lifecycle)
                    .addValue("effectiveHealth", effectiveHealth)
                    .addValue("tenantId", update.tenantId())
                    .addValue("agentId", update.agentId())
                    .addValue("serviceId", update.serviceId())
                    .addValue("instanceId", update.instanceId());
            return jdbc.update(sql, params) > 0;
        });
    }

    /**
     * queryByTargetSelector.
     *
     * @param filter filter
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<DiscoveryRow> queryByTargetSelector(DiscoveryFilter filter) {
        Objects.requireNonNull(filter, "filter");
        requireNonBlank(filter.tenantId(), "tenantId");
        StringBuilder sql = new StringBuilder(
                "SELECT service_id, agent_id, deployment_service_id, agent_name, framework_type, "
                + "route_key, contract_version, capability_version, card_digest, weight, region, "
                + "max_concurrency, lifecycle_status, effective_health, freshness, "
                + "last_validated_at, lease_expires_at, a2a_agent_card::text AS a2a_agent_card_json "
                + "FROM " + TABLE + " WHERE tenant_id = :tenantId");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", filter.tenantId());
        if (filter.agentId() != null && !filter.agentId().isBlank()) {
            sql.append(" AND agent_id = :agentId");
            params.addValue("agentId", filter.agentId());
        }
        if (filter.serviceId() != null && !filter.serviceId().isBlank()) {
            sql.append(" AND service_id = :serviceId");
            params.addValue("serviceId", filter.serviceId());
        }
        if (filter.a2aSkillId() != null && !filter.a2aSkillId().isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements(a2a_agent_card -> 'skills') sk"
                    + " WHERE sk ->> 'id' = :a2aSkillId)");
            params.addValue("a2aSkillId", filter.a2aSkillId());
        }
        sql.append(" ORDER BY agent_id, service_id, weight DESC, last_validated_at DESC NULLS LAST");
        if (filter.limit() > 0) {
            sql.append(" LIMIT :limit");
            params.addValue("limit", filter.limit());
        }
        return withTenant(filter.tenantId(), () ->
                jdbc.query(sql.toString(), params, DiscoveryRowMapper.INSTANCE));
    }

    /**
     * reconcileUpsert.
     *
     * @param command command
     * @since 0.1.0
     */
    @Override
    public void reconcileUpsert(ReconcileUpsertCommand command) {
        Objects.requireNonNull(command, "command");
        requireNonBlank(command.tenantId(), "tenantId");
        requireNonBlank(command.agentId(), "agentId");
        requireNonBlank(command.serviceId(), "serviceId");
        withTenant(command.tenantId(), () -> {
            jdbc.update(reconcileUpsertSql(), reconcileUpsertParams(command));
            syncLogicalFromReconcileUpsert(command);
            return null;
        });
    }

    private static String reconcileUpsertSql() {
        return "INSERT INTO " + TABLE + " ("
                + "tenant_id, agent_id, service_id, instance_id, deployment_service_id, "
                + "source_id, source_revision, "
                + "agent_name, framework_type, route_key, contract_version, capability_version, "
                + "endpoint_url, max_concurrency, weight, region, a2a_agent_card, card_digest, "
                + "route_target, status, last_heartbeat, lifecycle_status, effective_health, "
                + "freshness, lease_expires_at, last_validated_at, draining_since) "
                + "VALUES (:tenantId, :agentId, :serviceId, :instanceId, :deploymentServiceId, "
                + ":sourceId, :sourceRevision, "
                + ":agentName, :frameworkType, :routeKey, :contractVersion, :capabilityVersion, "
                + ":endpointUrl, :maxConcurrency, :weight, :region, :a2aAgentCard::jsonb, "
                + ":cardDigest, :routeTarget::jsonb, :status, CURRENT_TIMESTAMP, :lifecycleStatus, "
                + ":effectiveHealth, :freshness, CURRENT_TIMESTAMP + INTERVAL '1 hour', "
                + "CURRENT_TIMESTAMP, NULL) "
                + "ON CONFLICT (tenant_id, agent_id, service_id, instance_id) DO UPDATE SET "
                + "instance_id = EXCLUDED.instance_id, "
                + "deployment_service_id = EXCLUDED.deployment_service_id, "
                + "source_id = EXCLUDED.source_id, "
                + "source_revision = EXCLUDED.source_revision, "
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
                + "card_digest = EXCLUDED.card_digest, "
                + "route_target = EXCLUDED.route_target, "
                + "status = EXCLUDED.status, "
                + "lifecycle_status = EXCLUDED.lifecycle_status, "
                + "effective_health = EXCLUDED.effective_health, "
                + "freshness = EXCLUDED.freshness, "
                + "lease_expires_at = CURRENT_TIMESTAMP + INTERVAL '1 hour', "
                + "last_validated_at = CURRENT_TIMESTAMP, "
                + "draining_since = NULL, "
                + "last_heartbeat = CURRENT_TIMESTAMP";
    }

    private static MapSqlParameterSource reconcileUpsertParams(ReconcileUpsertCommand command) {
        return new MapSqlParameterSource()
                .addValue("tenantId", command.tenantId())
                .addValue("agentId", command.agentId())
                .addValue("serviceId", command.serviceId())
                .addValue("instanceId", command.instanceId())
                .addValue("deploymentServiceId", command.deploymentServiceId())
                .addValue("sourceId", command.sourceId())
                .addValue("sourceRevision", command.sourceRevision())
                .addValue("agentName", command.agentName())
                .addValue("frameworkType", command.frameworkType().name())
                .addValue("routeKey", command.routeKey())
                .addValue("contractVersion", command.contractVersion())
                .addValue("capabilityVersion", command.capabilityVersion())
                .addValue("endpointUrl", command.endpointUrl())
                .addValue("maxConcurrency", command.maxConcurrency())
                .addValue("weight", command.weight())
                .addValue("region", command.region())
                .addValue("a2aAgentCard", command.a2aAgentCardJson())
                .addValue("cardDigest", command.cardDigest())
                .addValue("routeTarget", command.routeTargetJson())
                .addValue("status", command.status())
                .addValue("lifecycleStatus", command.lifecycleStatus())
                .addValue("effectiveHealth", command.effectiveHealth())
                .addValue("freshness", command.freshness());
    }

    /**
     * listInstanceKeysBySource.
     *
     * @param sourceId sourceId
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<InstanceKey> listInstanceKeysBySource(String sourceId) {
        requireNonBlank(sourceId, "sourceId");
        String tenantSql = "SELECT DISTINCT tenant_id FROM " + TABLE + " WHERE source_id = :sourceId";
        List<String> tenants = jdbc.query(tenantSql, new MapSqlParameterSource("sourceId", sourceId),
                (rs, rowNum) -> rs.getString(1));
        List<InstanceKey> keys = new java.util.ArrayList<>();
        String instanceSql = "SELECT tenant_id, agent_id, service_id, instance_id FROM " + TABLE
                + " WHERE source_id = :sourceId AND lifecycle_status IN ('ACTIVE','PENDING','DRAINING')";
        for (String tenantId : tenants) {
            keys.addAll(withTenant(tenantId, () -> jdbc.query(instanceSql,
                    new MapSqlParameterSource("sourceId", sourceId),
                    (rs, rowNum) -> new InstanceKey(
                            rs.getString("tenant_id"),
                            rs.getString("agent_id"),
                            rs.getString("service_id"),
                            rs.getString("instance_id")))));
        }
        return keys;
    }

    /**
     * markDraining.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @since 0.1.0
     */
    @Override
    public void markDraining(String tenantId, String agentId, String serviceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        withTenant(tenantId, () -> {
            String sql = "UPDATE " + TABLE + " SET lifecycle_status = 'DRAINING', status = 'DRAINING', "
                    + "draining_since = COALESCE(draining_since, CURRENT_TIMESTAMP) "
                    + "WHERE tenant_id = :tenantId AND agent_id = :agentId AND service_id = :serviceId";
            jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("serviceId", serviceId));
            removeLogicalSourceForInstance(tenantId, agentId, serviceId);
            return null;
        });
    }

    /**
     * markRemoved.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @since 0.1.0
     */
    @Override
    public void markRemoved(String tenantId, String agentId, String serviceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        withTenant(tenantId, () -> {
            String sql = "UPDATE " + TABLE + " SET lifecycle_status = 'REMOVED', status = 'OFFLINE', "
                    + "effective_health = 'UNHEALTHY' "
                    + "WHERE tenant_id = :tenantId AND agent_id = :agentId AND service_id = :serviceId";
            jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("serviceId", serviceId));
            return null;
        });
    }

    /**
     * markSourceStale.
     *
     * @param sourceId sourceId
     * @since 0.1.0
     */
    @Override
    public void markSourceStale(String sourceId) {
        requireNonBlank(sourceId, "sourceId");
        jdbc.update("UPDATE " + TABLE + " SET freshness = 'STALE_SOURCE' WHERE source_id = :sourceId",
                new MapSqlParameterSource("sourceId", sourceId));
        markLogicalRegistrationsStaleSource(sourceId);
    }

    /**
     * markSourceFresh.
     *
     * @param sourceId sourceId
     * @since 0.1.0
     */
    @Override
    public void markSourceFresh(String sourceId) {
        requireNonBlank(sourceId, "sourceId");
        jdbc.update("UPDATE " + TABLE + " SET freshness = 'FRESH' "
                        + "WHERE source_id = :sourceId AND freshness = 'STALE_SOURCE'",
                new MapSqlParameterSource("sourceId", sourceId));
        jdbc.update("UPDATE " + REGISTRATION_TABLE + " SET freshness = 'FRESH', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE registration_id IN (SELECT registration_id FROM " + SOURCE_REF_TABLE
                        + " WHERE source_id = :sourceId) AND freshness = 'STALE_SOURCE'",
                new MapSqlParameterSource("sourceId", sourceId));
    }

    /**
     * listExpiredLeases.
     *
     * @param now now
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<InstanceKey> listExpiredLeases(java.time.Instant now) {
        Objects.requireNonNull(now, "now");
        String sql = "SELECT tenant_id, agent_id, service_id, instance_id FROM " + TABLE
                + " WHERE lifecycle_status = 'ACTIVE' AND lease_expires_at IS NOT NULL "
                + "AND lease_expires_at <= :now";
        return jdbc.query(sql, new MapSqlParameterSource("now", java.sql.Timestamp.from(now)),
                (rs, rowNum) -> new InstanceKey(
                        rs.getString("tenant_id"),
                        rs.getString("agent_id"),
                        rs.getString("service_id"),
                        rs.getString("instance_id")));
    }

    /**
     * getLastProcessedRevision.
     *
     * @param sourceId sourceId
     * @return result
     * @since 0.1.0
     */
    @Override
    public long getLastProcessedRevision(String sourceId) {
        requireNonBlank(sourceId, "sourceId");
        List<Long> rows = jdbc.query(
                "SELECT last_processed_revision FROM registry_source_state WHERE source_id = :sourceId",
                new MapSqlParameterSource("sourceId", sourceId),
                (rs, rowNum) -> rs.getLong(1));
        return rows.isEmpty() ? 0L : rows.get(0);
    }

    /**
     * updateLastProcessedRevision.
     *
     * @param sourceId sourceId
     * @param revision revision
     * @since 0.1.0
     */
    @Override
    public void updateLastProcessedRevision(String sourceId, long revision) {
        updateLastProcessedRevision(sourceId, revision, null);
    }

    /**
     * getSnapshotFingerprint.
     *
     * @param sourceId sourceId
     * @return result
     * @since 0.1.0
     */
    @Override
    public java.util.Optional<String> getSnapshotFingerprint(String sourceId) {
        requireNonBlank(sourceId, "sourceId");
        List<String> rows = jdbc.query(
                "SELECT snapshot_fingerprint FROM registry_source_state WHERE source_id = :sourceId",
                new MapSqlParameterSource("sourceId", sourceId),
                (rs, rowNum) -> rs.getString(1));
        if (rows.isEmpty() || rows.get(0) == null || rows.get(0).isBlank()) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(rows.get(0));
    }

    /**
     * updateLastProcessedRevision.
     *
     * @param sourceId sourceId
     * @param revision revision
     * @param snapshotFingerprint snapshotFingerprint
     * @since 0.1.0
     */
    @Override
    public void updateLastProcessedRevision(String sourceId, long revision, String snapshotFingerprint) {
        requireNonBlank(sourceId, "sourceId");
        jdbc.update("INSERT INTO registry_source_state "
                        + "(source_id, last_processed_revision, last_success_at, snapshot_fingerprint) "
                        + "VALUES (:sourceId, :revision, CURRENT_TIMESTAMP, :fingerprint) "
                        + "ON CONFLICT (source_id) DO UPDATE SET "
                        + "last_processed_revision = EXCLUDED.last_processed_revision, "
                        + "last_success_at = CURRENT_TIMESTAMP, "
                        + "snapshot_fingerprint = EXCLUDED.snapshot_fingerprint",
                new MapSqlParameterSource()
                        .addValue("sourceId", sourceId)
                        .addValue("revision", revision)
                        .addValue("fingerprint", snapshotFingerprint));
    }

    /**
     * findCardDigest.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @return result
     * @since 0.1.0
     */
    @Override
    public java.util.Optional<String> findCardDigest(String tenantId, String agentId, String serviceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        return withTenant(tenantId, () -> {
            List<String> rows = jdbc.query(
                    "SELECT card_digest FROM " + TABLE
                            + " WHERE tenant_id = :tenantId AND agent_id = :agentId AND service_id = :serviceId",
                    new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("agentId", agentId)
                            .addValue("serviceId", serviceId),
                    (rs, rowNum) -> rs.getString(1));
            return rows.isEmpty() || rows.get(0) == null
                    ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
        });
    }

    /**
     * reconcilePending.
     *
     * @param command command
     * @since 0.1.0
     */
    @Override
    public void reconcilePending(ReconcilePendingCommand command) {
        Objects.requireNonNull(command, "command");
        String sql = "INSERT INTO " + TABLE + " ("
                + "tenant_id, agent_id, service_id, instance_id, deployment_service_id, "
                + "source_id, source_revision, agent_name, framework_type, endpoint_url, region, "
                + "route_key, contract_version, capability_version, max_concurrency, weight, "
                + "status, lifecycle_status, effective_health, freshness, lease_expires_at) "
                + "VALUES (:tenantId, :agentId, :serviceId, :instanceId, :deploymentServiceId, "
                + ":sourceId, :sourceRevision, :agentName, :frameworkType, :endpointUrl, :region, "
                + "'pending', '0.0.0', '0.0.0', 10, 100, 'OFFLINE', 'PENDING', 'UNKNOWN', 'FRESH', "
                + "CURRENT_TIMESTAMP + INTERVAL '1 hour') "
                + "ON CONFLICT (tenant_id, agent_id, service_id, instance_id) DO UPDATE SET "
                + "source_id = EXCLUDED.source_id, source_revision = EXCLUDED.source_revision, "
                + "lifecycle_status = 'PENDING', effective_health = 'UNKNOWN', endpoint_url = EXCLUDED.endpoint_url";
        withTenant(command.tenantId(), () -> {
            jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("tenantId", command.tenantId())
                    .addValue("agentId", command.agentId())
                    .addValue("serviceId", command.serviceId())
                    .addValue("instanceId", command.instanceId())
                    .addValue("deploymentServiceId", command.deploymentServiceId())
                    .addValue("sourceId", command.sourceId())
                    .addValue("sourceRevision", command.sourceRevision())
                    .addValue("agentName", "pending-" + command.instanceId())
                    .addValue("frameworkType", command.frameworkType().name())
                    .addValue("endpointUrl", command.endpointUrl())
                    .addValue("region", command.region()));
            syncLogicalFromReconcilePending(command);
            return null;
        });
    }

    /**
     * markRefreshDegraded.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @since 0.1.0
     */
    @Override
    public void markRefreshDegraded(String tenantId, String agentId, String serviceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        withTenant(tenantId, () -> {
            // Instance index keeps last-valid snapshot freshness (FRESH/STALE_SOURCE only).
            // STALE_CARD is a logical-registration freshness (agent_card_registration).
            jdbc.update("UPDATE " + TABLE + " SET effective_health = 'DEGRADED', status = 'DEGRADED' "
                            + "WHERE tenant_id = :tenantId AND agent_id = :agentId AND service_id = :serviceId",
                    new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("agentId", agentId)
                            .addValue("serviceId", serviceId));
            List<java.util.Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT capability_version, card_digest FROM " + TABLE
                            + " WHERE tenant_id = :tenantId AND agent_id = :agentId AND service_id = :serviceId",
                    new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("agentId", agentId)
                            .addValue("serviceId", serviceId));
            for (java.util.Map<String, Object> row : rows) {
                Optional<String> capabilityVersion = stringColumn(row, "capability_version");
                Optional<String> cardDigest = stringColumn(row, "card_digest");
                if (capabilityVersion.isPresent() && cardDigest.isPresent()) {
                    // Logical catalog keys by deployment service id (= stable agentId per AgentIdCodec).
                    markLogicalRegistrationStaleCard(
                            tenantId, agentId, capabilityVersion.get(), cardDigest.get());
                }
            }
            return null;
        });
    }

    /**
     * findForResolve.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @param instanceId instanceId
     * @return result
     * @since 0.1.0
     */
    @Override
    public java.util.Optional<ResolveRow> findForResolve(
            String tenantId, String agentId, String serviceId, String instanceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        requireNonBlank(serviceId, "serviceId");
        requireNonBlank(instanceId, "instanceId");
        return withTenant(tenantId, () -> {
            String sql = "SELECT endpoint_url, route_key, contract_version, capability_version, "
                    + "lifecycle_status, lease_expires_at "
                    + "FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND service_id = :serviceId AND instance_id = :instanceId";
            List<ResolveRow> rows = jdbc.query(sql, new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("agentId", agentId)
                            .addValue("serviceId", serviceId)
                            .addValue("instanceId", instanceId),
                    (rs, rowNum) -> new ResolveRow(
                            rs.getString("endpoint_url"),
                            rs.getString("route_key"),
                            rs.getString("contract_version"),
                            rs.getString("capability_version"),
                            rs.getString("lifecycle_status"),
                            rs.getTimestamp("lease_expires_at") != null
                                    ? rs.getTimestamp("lease_expires_at").toInstant() : null));
            return rows.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(rows.get(0));
        });
    }

    /**
     * listDrainingPastGrace.
     *
     * @param cutoff cutoff
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<InstanceKey> listDrainingPastGrace(java.time.Instant cutoff) {
        Objects.requireNonNull(cutoff, "cutoff");
        String sql = "SELECT tenant_id, agent_id, service_id, instance_id FROM " + TABLE
                + " WHERE lifecycle_status = 'DRAINING' AND draining_since IS NOT NULL "
                + "AND draining_since < :cutoff";
        return jdbc.query(sql,
                new MapSqlParameterSource("cutoff", java.sql.Timestamp.from(cutoff)),
                (rs, rowNum) -> new InstanceKey(
                        rs.getString("tenant_id"),
                        rs.getString("agent_id"),
                        rs.getString("service_id"),
                        rs.getString("instance_id")));
    }

    private static String mapStatusToEffectiveHealth(String status) {
        return switch (status) {
            case "ONLINE" -> "HEALTHY";
            case "DEGRADED" -> "DEGRADED";
            case "DRAINING" -> "HEALTHY";
            default -> "UNHEALTHY";
        };
    }

    private static String mapStatusToLifecycle(String status) {
        return switch (status) {
            case "ONLINE", "DEGRADED" -> "ACTIVE";
            case "DRAINING" -> "DRAINING";
            default -> "REMOVED";
        };
    }

    /**
     * listByAgentId.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param contractVersion contractVersion
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<RegistryRow> listByAgentId(String tenantId, String agentId, String contractVersion) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(agentId, "agentId");
        return withTenant(tenantId, () -> {
            String sql = "SELECT service_id, instance_id, agent_id, agent_name, framework_type, "
                    + "route_key, contract_version, capability_version, "
                    + "weight, region, max_concurrency, status, capabilities FROM " + TABLE
                    + " WHERE tenant_id = :tenantId AND agent_id = :agentId"
                    + " AND status IN ('ONLINE','DEGRADED','DRAINING')"
                    + " AND (CAST(:contractVersion AS varchar) IS NULL OR contract_version = :contractVersion)"
                    + " ORDER BY weight DESC, last_heartbeat DESC";
            MapSqlParameterSource params = new MapSqlParameterSource()
                    .addValue("tenantId", tenantId)
                    .addValue("agentId", agentId)
                    .addValue("contractVersion", contractVersion);
            return jdbc.query(sql, params, RegistryRowMapper.INSTANCE);
        });
    }

    /**
     * listByServiceId.
     *
     * @param tenantId tenantId
     * @param serviceId serviceId
     * @param contractVersion contractVersion
     * @return result
     * @since 0.1.0
     */
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

    /**
     * listByCapability.
     *
     * @param tenantId tenantId
     * @param capability capability
     * @param contractVersion contractVersion
     * @return result
     * @since 0.1.0
     */
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

    /**
     * findEndpoint.
     *
     * @param tenantId tenantId
     * @param agentId agentId
     * @param serviceId serviceId
     * @param instanceId instanceId
     * @return result
     * @since 0.1.0
     */
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
     * Stage 24 RLS wiring — see class javadoc.
     *
     * @param tenantId tenantId
     * @param work work
     * @return result
     * @since 0.1.0
     */
    private <T> T withTenant(String tenantId, Supplier<T> work) {
        return txTemplate.execute(status -> {
            jdbc.queryForObject("SELECT set_config('app.tenant_id', :tenantId, true)",
                    new MapSqlParameterSource("tenantId", tenantId), String.class);
            return work.get();
        });
    }

    private static Optional<String> stringColumn(java.util.Map<String, Object> row, String column) {
        Object value = row.get(column);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(value instanceof String s ? s : value.toString());
    }

    private static Optional<java.util.UUID> uuidColumn(Object value) throws java.sql.SQLException {
            if (value == null) {
                return Optional.empty();
            }
        if (value instanceof java.util.UUID uuid) {
            return Optional.of(uuid);
        }
        throw new java.sql.SQLException("expected UUID column value, got " + value.getClass().getName());
    }

    /**
     * Row mapper for {@link RegistryRow} — single instance, stateless.
     */
    private static final class RegistryRowMapper
            implements org.springframework.jdbc.core.RowMapper<RegistryRow> {
        static final RegistryRowMapper INSTANCE = new RegistryRowMapper();

        /**
         * mapRow.
         *
         * @param rs rs
         * @param rowNum rowNum
         * @return result
         * @since 0.1.0
         * @throws java.sql.SQLException java.sql.SQLException
         */
        @Override
        public RegistryRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            String frameworkTypeName = rs.getString("framework_type");
            FrameworkType frameworkType = frameworkTypeName == null
                    ? null : FrameworkType.valueOf(frameworkTypeName);
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
            if (raw instanceof String[] caps) {
                return caps.length == 0 ? List.of() : List.copyOf(Arrays.asList(caps));
            }
            return List.of();
        }
    }

    /**
     * Row mapper for {@link DiscoveryRow}.
     */
    private static final class DiscoveryRowMapper
            implements org.springframework.jdbc.core.RowMapper<DiscoveryRow> {
        static final DiscoveryRowMapper INSTANCE = new DiscoveryRowMapper();

        /**
         * mapRow.
         *
         * @param rs rs
         * @param rowNum rowNum
         * @return result
         * @since 0.1.0
         * @throws java.sql.SQLException java.sql.SQLException
         */
        @Override
        public DiscoveryRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            String frameworkTypeName = rs.getString("framework_type");
            FrameworkType frameworkType = frameworkTypeName == null
                    ? null : FrameworkType.valueOf(frameworkTypeName);
            java.sql.Timestamp lastValidated = rs.getTimestamp("last_validated_at");
            java.sql.Timestamp leaseExpires = rs.getTimestamp("lease_expires_at");
            return new DiscoveryRow(
                    rs.getString("service_id"),
                    rs.getString("agent_id"),
                    rs.getString("deployment_service_id"),
                    rs.getString("agent_name"),
                    frameworkType,
                    rs.getString("route_key"),
                    rs.getString("contract_version"),
                    rs.getString("capability_version"),
                    rs.getString("card_digest"),
                    rs.getInt("weight"),
                    rs.getString("region"),
                    rs.getInt("max_concurrency"),
                    rs.getString("lifecycle_status"),
                    rs.getString("effective_health"),
                    rs.getString("freshness"),
                    lastValidated == null ? null : lastValidated.toInstant(),
                    leaseExpires == null ? null : leaseExpires.toInstant(),
                    rs.getString("a2a_agent_card_json"));
        }
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * upsertLogicalRegistration.
     *
     * @param command command
     * @return result
     * @since 0.1.0
     */
    @Override
    public java.util.UUID upsertLogicalRegistration(UpsertLogicalRegistrationCommand command) {
        Objects.requireNonNull(command, "command");
        requireNonBlank(command.tenantId(), "tenantId");
        requireNonBlank(command.agentId(), "agentId");
        requireNonBlank(command.deploymentServiceId(), "deploymentServiceId");
        requireNonBlank(command.cardDigest(), "cardDigest");
        requireNonBlank(command.contractVersion(), "contractVersion");
        requireNonBlank(command.capabilityVersion(), "capabilityVersion");
        requireNonBlank(command.registrationStatus(), "registrationStatus");
        requireNonBlank(command.freshness(), "freshness");
        return withTenant(command.tenantId(), () -> {
            String sql = "INSERT INTO " + REGISTRATION_TABLE + " ("
                    + "tenant_id, agent_id, service_id, card_digest, contract_version, "
                    + "capability_version, registration_status, freshness, last_validated_at, "
                    + "a2a_agent_card) "
                    + "VALUES (:tenantId, :agentId, :serviceId, :cardDigest, :contractVersion, "
                    + ":capabilityVersion, :registrationStatus, :freshness, "
                    + "CASE WHEN :registrationStatus = 'REGISTERED' THEN CURRENT_TIMESTAMP ELSE NULL END, "
                    + ":a2aAgentCard::jsonb) "
                    + "ON CONFLICT (tenant_id, service_id, capability_version, card_digest) DO UPDATE SET "
                    + "agent_id = EXCLUDED.agent_id, "
                    + "contract_version = EXCLUDED.contract_version, "
                    + "registration_status = EXCLUDED.registration_status, "
                    + "freshness = EXCLUDED.freshness, "
                    + "a2a_agent_card = COALESCE(EXCLUDED.a2a_agent_card, "
                    + REGISTRATION_TABLE + ".a2a_agent_card), "
                    + "last_validated_at = CASE WHEN EXCLUDED.registration_status = 'REGISTERED' "
                    + "THEN CURRENT_TIMESTAMP ELSE " + REGISTRATION_TABLE + ".last_validated_at END, "
                    + "revision = " + REGISTRATION_TABLE + ".revision + 1, "
                    + "updated_at = CURRENT_TIMESTAMP "
                    + "RETURNING registration_id";
            List<java.util.UUID> ids = jdbc.query(sql, logicalRegistrationParams(command),
                    (rs, rowNum) -> uuidColumn(rs.getObject("registration_id")).orElse(null));
            return ids.get(0);
        });
    }

    private static MapSqlParameterSource logicalRegistrationParams(UpsertLogicalRegistrationCommand command) {
        return new MapSqlParameterSource()
                .addValue("tenantId", command.tenantId())
                .addValue("agentId", command.agentId())
                .addValue("serviceId", command.deploymentServiceId())
                .addValue("cardDigest", command.cardDigest())
                .addValue("contractVersion", command.contractVersion())
                .addValue("capabilityVersion", command.capabilityVersion())
                .addValue("registrationStatus", command.registrationStatus())
                .addValue("freshness", command.freshness())
                .addValue("a2aAgentCard", command.a2aAgentCardJson());
    }

    /**
     * upsertSourceRef.
     *
     * @param command command
     * @since 0.1.0
     */
    @Override
    public void upsertSourceRef(UpsertSourceRefCommand command) {
        Objects.requireNonNull(command, "command");
        requireNonBlank(command.tenantId(), "tenantId");
        requireNonBlank(command.deploymentServiceId(), "deploymentServiceId");
        requireNonBlank(command.instanceId(), "instanceId");
        requireNonBlank(command.sourceId(), "sourceId");
        requireNonBlank(command.readiness(), "readiness");
        Objects.requireNonNull(command.registrationId(), "registrationId");
        withTenant(command.tenantId(), () -> {
            // Card digest change creates a new logical registration row; moving this
            // instance's source_ref must recompute the previous registration so it
            // exits the discovery directory when it loses its last source (Feat-015 §5.1.3).
            List<java.util.UUID> prior = jdbc.query(
                    "SELECT registration_id FROM " + SOURCE_REF_TABLE
                            + " WHERE tenant_id = :tenantId AND service_id = :serviceId "
                            + "AND instance_id = :instanceId",
                    new MapSqlParameterSource()
                            .addValue("tenantId", command.tenantId())
                            .addValue("serviceId", command.deploymentServiceId())
                            .addValue("instanceId", command.instanceId()),
                    (rs, rowNum) -> uuidColumn(rs.getObject("registration_id")).orElse(null));
            java.util.UUID previousRegistrationId = prior.isEmpty() ? null : prior.get(0);

            String sql = "INSERT INTO " + SOURCE_REF_TABLE + " ("
                    + "tenant_id, service_id, instance_id, source_id, source_revision, "
                    + "internal_base_url, deployment_version, readiness, registration_id) "
                    + "VALUES (:tenantId, :serviceId, :instanceId, :sourceId, :sourceRevision, "
                    + ":internalBaseUrl, :deploymentVersion, :readiness, :registrationId) "
                    + "ON CONFLICT (tenant_id, service_id, instance_id) DO UPDATE SET "
                    + "source_id = EXCLUDED.source_id, "
                    + "source_revision = EXCLUDED.source_revision, "
                    + "internal_base_url = EXCLUDED.internal_base_url, "
                    + "deployment_version = EXCLUDED.deployment_version, "
                    + "readiness = EXCLUDED.readiness, "
                    + "registration_id = EXCLUDED.registration_id, "
                    + "observed_at = CURRENT_TIMESTAMP";
            jdbc.update(sql, new MapSqlParameterSource()
                    .addValue("tenantId", command.tenantId())
                    .addValue("serviceId", command.deploymentServiceId())
                    .addValue("instanceId", command.instanceId())
                    .addValue("sourceId", command.sourceId())
                    .addValue("sourceRevision", command.sourceRevision())
                    .addValue("internalBaseUrl", command.internalBaseUrl())
                    .addValue("deploymentVersion", command.deploymentVersion())
                    .addValue("readiness", command.readiness())
                    .addValue("registrationId", command.registrationId()));

            if (previousRegistrationId != null
                    && !previousRegistrationId.equals(command.registrationId())) {
                recomputeRegistrationStatus(previousRegistrationId);
            }
            return null;
        });
    }

    /**
     * removeSourceRef.
     *
     * @param tenantId tenantId
     * @param deploymentServiceId deploymentServiceId
     * @param instanceId instanceId
     * @since 0.1.0
     */
    @Override
    public void removeSourceRef(String tenantId, String deploymentServiceId, String instanceId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(deploymentServiceId, "deploymentServiceId");
        requireNonBlank(instanceId, "instanceId");
        withTenant(tenantId, () -> {
            List<java.util.UUID> affected = jdbc.query(
                    "SELECT registration_id FROM " + SOURCE_REF_TABLE
                            + " WHERE tenant_id = :tenantId AND service_id = :serviceId AND instance_id = :instanceId",
                    new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("serviceId", deploymentServiceId)
                            .addValue("instanceId", instanceId),
                    (rs, rowNum) -> uuidColumn(rs.getObject("registration_id")).orElse(null));
            jdbc.update("DELETE FROM " + SOURCE_REF_TABLE
                            + " WHERE tenant_id = :tenantId AND service_id = :serviceId AND instance_id = :instanceId",
                    new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("serviceId", deploymentServiceId)
                            .addValue("instanceId", instanceId));
            for (java.util.UUID registrationId : affected) {
                recomputeRegistrationStatus(registrationId);
            }
            return null;
        });
    }

    /**
     * recomputeRegistrationStatus.
     *
     * @param registrationId registrationId
     * @since 0.1.0
     */
    @Override
    public void recomputeRegistrationStatus(java.util.UUID registrationId) {
        Objects.requireNonNull(registrationId, "registrationId");
        String sql = "UPDATE " + REGISTRATION_TABLE + " SET registration_status = CASE "
                + "WHEN (SELECT COUNT(*) FROM " + SOURCE_REF_TABLE + " ref "
                + "WHERE ref.registration_id = :registrationId AND ref.readiness = 'READY') > 0 "
                + "AND a2a_agent_card IS NOT NULL THEN 'REGISTERED' "
                + "WHEN (SELECT COUNT(*) FROM " + SOURCE_REF_TABLE + " ref "
                + "WHERE ref.registration_id = :registrationId) > 0 THEN 'PENDING' "
                + "ELSE 'REMOVED' END, "
                + "updated_at = CURRENT_TIMESTAMP "
                + "WHERE registration_id = :registrationId";
        jdbc.update(sql, new MapSqlParameterSource("registrationId", registrationId));
    }

    /**
     * markLogicalRegistrationsStaleSource.
     *
     * @param sourceId sourceId
     * @since 0.1.0
     */
    @Override
    public void markLogicalRegistrationsStaleSource(String sourceId) {
        requireNonBlank(sourceId, "sourceId");
        jdbc.update("UPDATE " + REGISTRATION_TABLE + " SET freshness = 'STALE_SOURCE', updated_at = CURRENT_TIMESTAMP "
                        + "WHERE registration_id IN (SELECT registration_id FROM " + SOURCE_REF_TABLE
                        + " WHERE source_id = :sourceId)",
                new MapSqlParameterSource("sourceId", sourceId));
    }

    /**
     * markLogicalRegistrationStaleCard.
     *
     * @param tenantId tenantId
     * @param deploymentServiceId deploymentServiceId
     * @param capabilityVersion capabilityVersion
     * @param cardDigest cardDigest
     * @since 0.1.0
     */
    @Override
    public void markLogicalRegistrationStaleCard(
            String tenantId, String deploymentServiceId, String capabilityVersion, String cardDigest) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(deploymentServiceId, "deploymentServiceId");
        requireNonBlank(capabilityVersion, "capabilityVersion");
        requireNonBlank(cardDigest, "cardDigest");
        withTenant(tenantId, () -> {
            jdbc.update("UPDATE " + REGISTRATION_TABLE
                    + " SET freshness = 'STALE_CARD', updated_at = CURRENT_TIMESTAMP "
                            + "WHERE tenant_id = :tenantId AND service_id = :serviceId "
                            + "AND capability_version = :capabilityVersion AND card_digest = :cardDigest",
                    new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("serviceId", deploymentServiceId)
                            .addValue("capabilityVersion", capabilityVersion)
                            .addValue("cardDigest", cardDigest));
            return null;
        });
    }

    /**
     * clearLogicalRegistrationStaleCard.
     *
     * @param tenantId tenantId
     * @param deploymentServiceId deploymentServiceId
     * @param cardDigest cardDigest
     * @since 0.1.0
     */
    @Override
    public void clearLogicalRegistrationStaleCard(
            String tenantId, String deploymentServiceId, String cardDigest) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(deploymentServiceId, "deploymentServiceId");
        requireNonBlank(cardDigest, "cardDigest");
        withTenant(tenantId, () -> {
            jdbc.update("UPDATE " + REGISTRATION_TABLE + " SET freshness = 'FRESH', "
                            + "last_validated_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP "
                            + "WHERE tenant_id = :tenantId AND service_id = :serviceId "
                            + "AND card_digest = :cardDigest AND freshness = 'STALE_CARD'",
                    new MapSqlParameterSource()
                            .addValue("tenantId", tenantId)
                            .addValue("serviceId", deploymentServiceId)
                            .addValue("cardDigest", cardDigest));
            return null;
        });
    }

    /**
     * relinkLogicalSourceRef.
     *
     * @param command command
     * @return result
     * @since 0.1.0
     */
    @Override
    public boolean relinkLogicalSourceRef(RelinkLogicalSourceRefCommand command) {
        Objects.requireNonNull(command, "command");
        requireNonBlank(command.tenantId(), "tenantId");
        requireNonBlank(command.deploymentServiceId(), "deploymentServiceId");
        requireNonBlank(command.instanceId(), "instanceId");
        requireNonBlank(command.cardDigest(), "cardDigest");
        requireNonBlank(command.sourceId(), "sourceId");
        requireNonBlank(command.internalBaseUrl(), "internalBaseUrl");
        return withTenant(command.tenantId(), () -> {
            List<java.util.UUID> ids = jdbc.query(
                    "SELECT registration_id FROM " + REGISTRATION_TABLE
                            + " WHERE tenant_id = :tenantId AND service_id = :serviceId "
                            + "AND card_digest = :cardDigest "
                            + "ORDER BY updated_at DESC NULLS LAST LIMIT 1",
                    new MapSqlParameterSource()
                            .addValue("tenantId", command.tenantId())
                            .addValue("serviceId", command.deploymentServiceId())
                            .addValue("cardDigest", command.cardDigest()),
                    (rs, rowNum) -> uuidColumn(rs.getObject("registration_id")).orElse(null));
            if (ids.isEmpty()) {
                return false;
            }
            java.util.UUID registrationId = ids.get(0);
            upsertSourceRef(new UpsertSourceRefCommand(
                    command.tenantId(),
                    command.deploymentServiceId(),
                    command.instanceId(),
                    command.sourceId(),
                    command.sourceRevision(),
                    command.internalBaseUrl(),
                    null,
                    "READY",
                    registrationId));
            recomputeRegistrationStatus(registrationId);
            return true;
        });
    }

    /**
     * queryLogicalByTargetSelector.
     *
     * @param filter filter
     * @return result
     * @since 0.1.0
     */
    @Override
    public List<LogicalRegistrationRow> queryLogicalByTargetSelector(DiscoveryFilter filter) {
        Objects.requireNonNull(filter, "filter");
        requireNonBlank(filter.tenantId(), "tenantId");
        StringBuilder sql = new StringBuilder(
                "SELECT registration_id, tenant_id, agent_id, service_id, card_digest, contract_version, "
                + "capability_version, registration_status, freshness, last_validated_at, revision, "
                + "a2a_agent_card::text AS a2a_agent_card_json "
                + "FROM " + REGISTRATION_TABLE + " WHERE tenant_id = :tenantId");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", filter.tenantId());
        if (filter.agentId() != null && !filter.agentId().isBlank()) {
            sql.append(" AND agent_id = :agentId");
            params.addValue("agentId", filter.agentId());
        }
        if (filter.serviceId() != null && !filter.serviceId().isBlank()) {
            sql.append(" AND service_id = :serviceId");
            params.addValue("serviceId", filter.serviceId());
        }
        if (filter.a2aSkillId() != null && !filter.a2aSkillId().isBlank()) {
            sql.append(" AND EXISTS (SELECT 1 FROM jsonb_array_elements(a2a_agent_card -> 'skills') sk"
                    + " WHERE sk ->> 'id' = :a2aSkillId)");
            params.addValue("a2aSkillId", filter.a2aSkillId());
        }
        sql.append(" ORDER BY agent_id, service_id, capability_version, last_validated_at DESC NULLS LAST");
        if (filter.limit() > 0) {
            sql.append(" LIMIT :limit");
            params.addValue("limit", filter.limit());
        }
        return withTenant(filter.tenantId(), () ->
                jdbc.query(sql.toString(), params, LogicalRegistrationRowMapper.INSTANCE));
    }

    private void syncLogicalFromReconcileUpsert(ReconcileUpsertCommand command) {
        java.util.UUID registrationId = upsertLogicalRegistration(new UpsertLogicalRegistrationCommand(
                command.tenantId(),
                command.agentId(),
                command.deploymentServiceId(),
                command.cardDigest(),
                command.contractVersion(),
                command.capabilityVersion(),
                "REGISTERED",
                command.freshness(),
                command.a2aAgentCardJson()));
        upsertSourceRef(new UpsertSourceRefCommand(
                command.tenantId(),
                command.deploymentServiceId(),
                command.instanceId(),
                command.sourceId(),
                command.sourceRevision(),
                command.endpointUrl(),
                null,
                "READY",
                registrationId));
        recomputeRegistrationStatus(registrationId);
    }

    private void syncLogicalFromReconcilePending(ReconcilePendingCommand command) {
        String placeholderDigest = "pending:" + command.instanceId();
        java.util.UUID registrationId = upsertLogicalRegistration(new UpsertLogicalRegistrationCommand(
                command.tenantId(),
                command.agentId(),
                command.deploymentServiceId(),
                placeholderDigest,
                "0.0.0",
                "0.0.0",
                "PENDING",
                "FRESH",
                null));
        upsertSourceRef(new UpsertSourceRefCommand(
                command.tenantId(),
                command.deploymentServiceId(),
                command.instanceId(),
                command.sourceId(),
                command.sourceRevision(),
                command.endpointUrl(),
                null,
                "READY",
                registrationId));
        recomputeRegistrationStatus(registrationId);
    }

    private void syncLogicalFromPushUpsert(AgentRegistryEntry entry, String a2aAgentCardJson) {
        if (a2aAgentCardJson == null || a2aAgentCardJson.isBlank()) {
            return;
        }
        String digest = CardDigest.sha256(a2aAgentCardJson);
        String logicalServiceId = entry.getAgentId();
        upsertLogicalRegistration(new UpsertLogicalRegistrationCommand(
                entry.getTenantId(),
                entry.getAgentId(),
                logicalServiceId,
                digest,
                entry.getContractVersion(),
                entry.getCapabilityVersion(),
                "REGISTERED",
                "FRESH",
                a2aAgentCardJson));
    }

    private void removeLogicalSourceForInstance(String tenantId, String agentId, String instanceId) {
        List<java.util.Map<String, Object>> rows = withTenant(tenantId, () -> jdbc.queryForList(
                "SELECT deployment_service_id FROM " + TABLE
                        + " WHERE tenant_id = :tenantId AND agent_id = :agentId AND instance_id = :instanceId",
                new MapSqlParameterSource()
                        .addValue("tenantId", tenantId)
                        .addValue("agentId", agentId)
                        .addValue("instanceId", instanceId)));
        for (java.util.Map<String, Object> row : rows) {
            String deploymentServiceId = stringColumn(row, "deployment_service_id").orElse(null);
            if (deploymentServiceId == null || deploymentServiceId.isBlank()) {
                deploymentServiceId = agentId;
            }
            removeSourceRef(tenantId, deploymentServiceId, instanceId);
        }
    }

    /**
     * Row mapper for {@link LogicalRegistrationRow}.
     */
    private static final class LogicalRegistrationRowMapper
            implements org.springframework.jdbc.core.RowMapper<LogicalRegistrationRow> {
        static final LogicalRegistrationRowMapper INSTANCE = new LogicalRegistrationRowMapper();

        /**
         * mapRow.
         *
         * @param rs rs
         * @param rowNum rowNum
         * @return result
         * @since 0.1.0
         * @throws java.sql.SQLException java.sql.SQLException
         */
        @Override
        public LogicalRegistrationRow mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
            java.sql.Timestamp lastValidated = rs.getTimestamp("last_validated_at");
            return new LogicalRegistrationRow(
                    uuidColumn(rs.getObject("registration_id")).orElse(null),
                    rs.getString("tenant_id"),
                    rs.getString("agent_id"),
                    rs.getString("service_id"),
                    rs.getString("card_digest"),
                    rs.getString("contract_version"),
                    rs.getString("capability_version"),
                    rs.getString("registration_status"),
                    rs.getString("freshness"),
                    lastValidated == null ? null : lastValidated.toInstant(),
                    rs.getLong("revision"),
                    rs.getString("a2a_agent_card_json"));
        }
    }
}
