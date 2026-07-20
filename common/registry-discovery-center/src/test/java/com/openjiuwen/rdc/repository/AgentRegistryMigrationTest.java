/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/**
 * V2/V3/V4/V5/V6 migration smoke test for {@code agent_registry_mvp}.
 *
 * <p>REQ-2026-004 V4 migration changes:
 * <ul>
 *   <li>Dropped {@code search_tsv} GENERATED column + GIN index
 *       {@code idx_agent_registry_mvp_search_tsv}.</li>
 *   <li>Dropped {@code capability} column + BTREE index
 *       {@code idx_agent_registry_mvp_tenant_capability}.</li>
 *   <li>Renamed {@code agent_type} → {@code framework_type}.</li>
 * </ul>
 *
 * <p>REQ-2026-006 V5 migration changes:
 * <ul>
 *   <li>Added {@code service_id VARCHAR(64)} column (NOT NULL after
 *       backfill).</li>
 *   <li>PK rebuilt from {@code (tenant_id, agent_id)} →
 *       {@code (tenant_id, agent_id, service_id)} so the same agentId can
 *       host N runtime instances.</li>
 * </ul>
 *
 * <p>FEAT-016 V6 migration changes:
 * <ul>
 *   <li>Renamed V5 {@code service_id} (host-port) → {@code instance_id}.</li>
 *   <li>Added new {@code service_id} (logical, host-only, backfilled from
 *       {@code endpoint_url} host).</li>
 *   <li>Added {@code capabilities VARCHAR(64)[]} column with default
 *       {@code '{}'}.</li>
 *   <li>PK rebuilt to 4-field {@code (tenant_id, agent_id, service_id,
 *       instance_id)}.</li>
 *   <li>GIN index {@code idx_agent_registry_mvp_capabilities_gin} on
 *       {@code capabilities} for by-capability discovery.</li>
 * </ul>
 *
 * <p>Tests verify the post-V6 schema state: PK is the 4-field tuple, partial
 * heartbeat index unchanged, RLS unchanged, CHECK constraints unchanged.
 * Old search_tsv / capability / agent_type artifacts must be gone.
 *
 * @since 2026-07-10
 */
class AgentRegistryMigrationTest {
    private static EmbeddedPostgres pg;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateAndWire() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        dataSource = pg.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        if (jdbc != null) {
            jdbc.execute("DELETE FROM agent_registry_mvp");
        }
    }

    @Test
    void v2_through_v6_migrations_applied_cleanly() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        assertThat(applied)
                .as("V2/V3/V4/V5/V6 migrations must all apply cleanly")
                .contains("2", "3", "4", "5", "6");
    }

    // ---- V6: serviceId/instanceId split + capabilities + 4-field PK ------

    @Test
    void v6_separates_service_and_instance_ids_adds_capabilities() {
        Integer pkCols = jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.key_column_usage "
                        + "WHERE table_name = 'agent_registry_mvp' "
                        + "AND constraint_name = 'agent_registry_mvp_pkey'",
                Integer.class);
        assertThat(pkCols)
                .as("FEAT-016 V6: PK must be 4 fields (tenant_id, agent_id, service_id, instance_id)")
                .isEqualTo(4);

        String serviceIdNullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name='agent_registry_mvp' AND column_name='service_id'",
                String.class);
        assertThat(serviceIdNullable)
                .as("FEAT-016 V6: service_id (logical, host-only) must be NOT NULL after backfill")
                .isEqualTo("NO");

        String instanceIdNullable = jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name='agent_registry_mvp' AND column_name='instance_id'",
                String.class);
        assertThat(instanceIdNullable)
                .as("FEAT-016 V6: instance_id (host-port, renamed from old service_id) must be NOT NULL")
                .isEqualTo("NO");

        String capType = jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name='agent_registry_mvp' AND column_name='capabilities'",
                String.class);
        assertThat(capType)
                .as("FEAT-016 V6: capabilities column must be ARRAY (VARCHAR(64)[])")
                .isEqualTo("ARRAY");
    }

    @Test
    void v6_creates_gin_index_on_capabilities() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'agent_registry_mvp' AND indexname = ?",
                Integer.class, "idx_agent_registry_mvp_capabilities_gin");
        assertThat(count)
                .as("FEAT-016 V6: GIN index on capabilities must be created")
                .isEqualTo(1);
    }

    @Test
    void agent_registry_mvp_has_composite_primary_key() {
        List<Map<String, Object>> pkColumns = jdbc.queryForList(
                "SELECT a.attname FROM pg_index i "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE i.indrelid = 'agent_registry_mvp'::regclass AND i.indisprimary "
                + "ORDER BY array_position(i.indkey, a.attnum)");
        assertThat(pkColumns)
                .as("FEAT-016 V6: agent_registry_mvp PK must be "
                    + "(tenant_id, agent_id, service_id, instance_id) — was "
                    + "(tenant_id, agent_id, service_id) in V5")
                .extracting(row -> row.get("attname"))
                .containsExactly("tenant_id", "agent_id", "service_id", "instance_id");
    }

    // ---- V5: service_id column added (NOT NULL) -------------------------

    @Test
    void v5_adds_service_id_column() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_attribute "
                + "WHERE attrelid = 'agent_registry_mvp'::regclass AND attname = 'service_id'",
                Integer.class);
        assertThat(count)
                .as("REQ-2026-006 V5: service_id column must exist")
                .isEqualTo(1);
    }

    @Test
    void v5_service_id_column_is_not_null() {
        Boolean isAttnotnull = jdbc.queryForObject(
                "SELECT attnotnull FROM pg_attribute "
                + "WHERE attrelid = 'agent_registry_mvp'::regclass AND attname = 'service_id'",
                Boolean.class);
        assertThat(isAttnotnull)
                .as("REQ-2026-006 V5: service_id column must be NOT NULL after backfill")
                .isTrue();
    }

    // ---- V4: search_tsv + GIN index DROPPED ------------------------------

    @Test
    void v4_drops_search_tsv_column() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_attribute "
                + "WHERE attrelid = 'agent_registry_mvp'::regclass AND attname = 'search_tsv'",
                Integer.class);
        assertThat(count)
                .as("REQ-2026-004 V4: search_tsv column must be dropped")
                .isZero();
    }

    @Test
    void v4_drops_search_tsv_gin_index() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'agent_registry_mvp' AND indexname = ?",
                Integer.class, "idx_agent_registry_mvp_search_tsv");
        assertThat(count)
                .as("REQ-2026-004 V4: idx_agent_registry_mvp_search_tsv GIN index must be dropped")
                .isZero();
    }

    // ---- V4: capability column + BTREE index DROPPED ---------------------

    @Test
    void v4_drops_capability_column() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_attribute "
                + "WHERE attrelid = 'agent_registry_mvp'::regclass AND attname = 'capability'",
                Integer.class);
        assertThat(count)
                .as("REQ-2026-004 V4: capability column must be dropped")
                .isZero();
    }

    @Test
    void v4_drops_tenant_capability_btree_index() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'agent_registry_mvp' AND indexname = ?",
                Integer.class, "idx_agent_registry_mvp_tenant_capability");
        assertThat(count)
                .as("REQ-2026-004 V4 D-3: idx_agent_registry_mvp_tenant_capability BTREE index must be dropped")
                .isZero();
    }

    // ---- V4: agent_type RENAMED to framework_type ------------------------

    @Test
    void v4_renames_agent_type_to_framework_type() {
        Integer frameworkCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_attribute "
                + "WHERE attrelid = 'agent_registry_mvp'::regclass AND attname = 'framework_type'",
                Integer.class);
        assertThat(frameworkCount)
                .as("REQ-2026-004 V4: framework_type column must exist (renamed from agent_type)")
                .isEqualTo(1);

        Integer agentTypeCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_attribute "
                + "WHERE attrelid = 'agent_registry_mvp'::regclass AND attname = 'agent_type'",
                Integer.class);
        assertThat(agentTypeCount)
                .as("REQ-2026-004 V4: agent_type column must be gone (renamed to framework_type)")
                .isZero();
    }

    // ---- RB1-S4: partial index on last_heartbeat WHERE status IN ('ONLINE','DEGRADED') ----

    @Test
    void partial_index_on_last_heartbeat_for_online_degraded_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'agent_registry_mvp' AND indexname = ?",
                Integer.class, "ix_agent_registry_mvp_heartbeat_due");
        assertThat(count).isEqualTo(1);

        String predicate = jdbc.queryForObject(
                "SELECT pg_get_expr(i.indpred, i.indrelid) FROM pg_index i "
                + "JOIN pg_class c ON c.oid = i.indrelid "
                + "JOIN pg_class ci ON ci.oid = i.indexrelid "
                + "WHERE c.relname = 'agent_registry_mvp' AND ci.relname = ?",
                String.class, "ix_agent_registry_mvp_heartbeat_due");
        assertThat(predicate)
                .as("HD3-004 + PR #389 #4: partial index must scope to "
                    + "status IN ('ONLINE','DEGRADED')")
                .contains("'ONLINE'")
                .contains("'DEGRADED'");
    }

    // ---- RB1-S5: CHECK on status lifecycle values ------------------------

    @Test
    void check_constraint_rejects_invalid_status() {
        insertValidRow("tenant-A", "agent-001");
        try {
            jdbc.update("UPDATE agent_registry_mvp SET status = 'INVALID' "
                    + "WHERE tenant_id = ? AND agent_id = ?", "tenant-A", "agent-001");
            org.assertj.core.api.Assertions.fail(
                    "CHECK constraint should have rejected status='INVALID'");
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            assertThat(ex.getMessage()).containsAnyOf("ck_agent_registry_mvp_status", "violates");
        }
    }

    @Test
    void check_constraint_accepts_all_lifecycle_values() {
        for (String status : new String[]{"ONLINE", "DEGRADED", "DRAINING", "OFFLINE"}) {
            insertValidRow("tenant-lifecycle", "agent-" + status);
            jdbc.update("UPDATE agent_registry_mvp SET status = ? "
                    + "WHERE tenant_id = ? AND agent_id = ?",
                    status, "tenant-lifecycle", "agent-" + status);
        }
    }

    @Test
    void check_constraint_rejects_negative_weight() {
        try {
            jdbc.update("INSERT INTO agent_registry_mvp ("
                    + "tenant_id, agent_id, service_id, instance_id, agent_name, "
                    + "framework_type, route_key, contract_version, "
                    + "capability_version, endpoint_url, weight) "
                    + "VALUES ('tenant-neg', 'agent-neg', 'svc-neg', 'inst-neg', 'name', 'JIUWEN', "
                    + "'rk', '1.0', '1.0', 'http://x', -1)");
            org.assertj.core.api.Assertions.fail(
                    "CHECK constraint should have rejected weight=-1");
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            assertThat(ex.getMessage()).containsAnyOf("ck_agent_registry_mvp_weight", "violates");
        }
    }

    // ---- RB1-S6: RLS policy enabled -------------------------------------

    @Test
    void rls_is_enabled_on_agent_registry_mvp() {
        Boolean isRlsEnabled = jdbc.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = 'agent_registry_mvp'",
                Boolean.class);
        assertThat(isRlsEnabled)
                .as("HD3-003 defence-in-depth: RLS must be enabled on agent_registry_mvp — V4 unchanged")
                .isTrue();
    }

    @Test
    void rls_policy_agent_registry_mvp_tenant_isolation_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_policies "
                + "WHERE tablename = 'agent_registry_mvp' "
                + "AND policyname = 'agent_registry_mvp_tenant_isolation'",
                Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ---- RB1-S7: RLS binds a restricted role -----------------------------

    @Test
    void rls_filters_rows_for_restricted_role_by_tenant_id() throws Exception {
        insertValidRow("tenant-A", "agent-rls-a");
        insertValidRow("tenant-B", "agent-rls-b");

        jdbc.execute("CREATE ROLE app_role_rls; "
                + "GRANT USAGE ON SCHEMA public TO app_role_rls; "
                + "GRANT SELECT ON agent_registry_mvp TO app_role_rls");

        try (java.sql.Connection restricted = dataSource.getConnection()) {
            Integer ownerCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_registry_mvp "
                    + "WHERE tenant_id IN ('tenant-A','tenant-B') "
                    + "AND agent_id IN ('agent-rls-a','agent-rls-b')",
                    Integer.class);
            assertThat(ownerCount).isEqualTo(2);

            try (java.sql.Statement st = restricted.createStatement()) {
                st.execute("SET ROLE app_role_rls");
                st.execute("SET app.tenant_id = 'tenant-A'");
                try (java.sql.ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM agent_registry_mvp "
                        + "WHERE tenant_id IN ('tenant-A','tenant-B') "
                        + "AND agent_id IN ('agent-rls-a','agent-rls-b')")) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1))
                            .as("Restricted role with app.tenant_id='tenant-A' must see only tenant-A's row")
                            .isEqualTo(1);
                }
            }
        }
    }

    // ---- helpers ---------------------------------------------------------

    private void insertValidRow(String tenantId, String agentId) {
        // FEAT-016 V6: PK is (tenant_id, agent_id, service_id, instance_id).
        // Both service_id (logical, host-only) and instance_id (host-port)
        // are NOT NULL + part of the PK. Use deterministic values derived
        // from the agentId so multiple rows in the same test don't collide.
        jdbc.update("INSERT INTO agent_registry_mvp ("
                + "tenant_id, agent_id, service_id, instance_id, agent_name, "
                + "framework_type, route_key, contract_version, "
                + "capability_version, endpoint_url, weight, status) "
                + "VALUES (?, ?, ?, ?, 'name', 'JIUWEN', "
                + "'rk', '1.0', '1.0', 'http://x', 100, 'ONLINE')",
                tenantId, agentId, "svc-" + agentId, "inst-" + agentId);
    }
}
