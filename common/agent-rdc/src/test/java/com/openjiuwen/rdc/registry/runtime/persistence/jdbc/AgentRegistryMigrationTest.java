package com.openjiuwen.rdc.registry.runtime.persistence.jdbc;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V2/V3/V4/V5 migration smoke test for {@code agent_registry_mvp}.
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
 * <p>Tests verify the post-V5 schema state: PK is the triple, partial
 * heartbeat index unchanged, RLS unchanged, CHECK constraints unchanged.
 * Old search_tsv / capability / agent_type artifacts must be gone.
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
    void v2_through_v5_migrations_applied_cleanly() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        assertThat(applied)
                .as("V2/V3/V4/V5 migrations must all apply cleanly")
                .contains("2", "3", "4", "5");
    }

    @Test
    void agent_registry_mvp_has_composite_primary_key() {
        List<Map<String, Object>> pkColumns = jdbc.queryForList(
                "SELECT a.attname FROM pg_index i "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE i.indrelid = 'agent_registry_mvp'::regclass AND i.indisprimary "
                + "ORDER BY array_position(i.indkey, a.attnum)");
        assertThat(pkColumns)
                .as("REQ-2026-006 V5: agent_registry_mvp PK must be "
                    + "(tenant_id, agent_id, service_id) — was (tenant_id, agent_id) pre-V5")
                .extracting(row -> row.get("attname"))
                .containsExactly("tenant_id", "agent_id", "service_id");
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
        Boolean attnotnull = jdbc.queryForObject(
                "SELECT attnotnull FROM pg_attribute "
                + "WHERE attrelid = 'agent_registry_mvp'::regclass AND attname = 'service_id'",
                Boolean.class);
        assertThat(attnotnull)
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
    void partial_index_on_last_heartbeat_for_online_and_degraded_rows_exists() {
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
                    + "tenant_id, agent_id, service_id, agent_name, framework_type, "
                    + "route_key, contract_version, capability_version, "
                    + "endpoint_url, weight) "
                    + "VALUES ('tenant-neg', 'agent-neg', 'svc-neg', 'name', 'JIUWEN', "
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
        Boolean rlsEnabled = jdbc.queryForObject(
                "SELECT relrowsecurity FROM pg_class WHERE relname = 'agent_registry_mvp'",
                Boolean.class);
        assertThat(rlsEnabled)
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
        // REQ-2026-006 V5: service_id is NOT NULL + part of the PK. Use a
        // deterministic service_id derived from the agentId so multiple rows
        // in the same test don't collide on the (tenant_id, agent_id,
        // service_id) PK.
        jdbc.update("INSERT INTO agent_registry_mvp ("
                + "tenant_id, agent_id, service_id, agent_name, framework_type, "
                + "route_key, contract_version, capability_version, "
                + "endpoint_url, weight, status) "
                + "VALUES (?, ?, ?, 'name', 'JIUWEN', "
                + "'rk', '1.0', '1.0', 'http://x', 100, 'ONLINE')",
                tenantId, agentId, "svc-" + agentId);
    }
}
