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
 * V2 migration smoke test for {@code V2__create_agent_registry_mvp.sql}
 * (RB1, ADR-0160 §3.2, HD3-002/003/004).
 *
 * <p>Boots an in-process PostgreSQL (Zonky embedded-postgres) and runs the
 * agent-rdc Flyway migration (V2 registry), then
 * verifies the {@code agent_registry_mvp} table:
 * <ul>
 *   <li><b>RB1-S1</b> — V2 applies cleanly (no SQL errors, no Flyway
 *       validation warnings).</li>
 *   <li><b>RB1-S2</b> — composite primary key {@code (tenant_id, agent_id)}
 *       is in place (HD3-003).</li>
 *   <li><b>RB1-S3</b> — {@code search_tsv} GENERATED column + GIN index
 *       exist (Method A/B tsvector ranking).</li>
 *   <li><b>RB1-S4</b> — partial index on {@code last_heartbeat WHERE
 *       status IN ('ONLINE','DEGRADED')} exists (HD3-004 lease/TTL scan
 *       path; PR #389 #4 widened from ONLINE-only to also cover DEGRADED
 *       so recovered agents can be re-probed and restored to ONLINE).</li>
 *   <li><b>RB1-S5</b> — {@code CHECK status IN ('ONLINE','DEGRADED',
 *       'DRAINING','OFFLINE')} constraint rejects invalid lifecycle values.</li>
 *   <li><b>RB1-S6</b> — Row-Level Security policy
 *       {@code agent_registry_mvp_tenant_isolation} is enabled (defence-in-depth
 *       tenant isolation, mirroring V1 §7.3).</li>
 *   <li><b>RB1-S7</b> — RLS binds a restricted (non-owner) role: a row
 *       inserted under {@code app.tenant_id='tenant-A'} is invisible when
 *       the restricted role queries with {@code app.tenant_id='tenant-B'}.</li>
 * </ul>
 *
 * <p>Authority: ADR-0160 §3.2 + HD3-002/003/004 + Stage 24 RLS wiring.
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
        // PR #389 review issue: tests share one EmbeddedPostgres instance
        // across cases; without a clean-table guard, rows inserted by one
        // case (e.g. RB1-S7 RLS visibility) leak into the next case's
        // assertions. The original tenant-prefix isolation worked for
        // read-only assertions but is fragile for any future write-and-count
        // case. DELETE is cheap and unambiguous.
        if (jdbc != null) {
            jdbc.execute("DELETE FROM agent_registry_mvp");
        }
    }

    // ---- RB1-S1: V2 applies cleanly --------------------------------------

    @Test
    void v2_migration_applies_cleanly() {
        List<String> applied = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
                String.class);
        assertThat(applied)
                .as("V2 (registry) migration must apply cleanly")
                .contains("2");
    }

    // ---- RB1-S2: composite primary key ----------------------------------

    @Test
    void agent_registry_mvp_has_composite_primary_key() {
        List<Map<String, Object>> pkColumns = jdbc.queryForList(
                "SELECT a.attname FROM pg_index i "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE i.indrelid = 'agent_registry_mvp'::regclass AND i.indisprimary "
                + "ORDER BY array_position(i.indkey, a.attnum)");
        assertThat(pkColumns)
                .as("HD3-003: agent_registry_mvp PK must be (tenant_id, agent_id)")
                .extracting(row -> row.get("attname"))
                .containsExactly("tenant_id", "agent_id");
    }

    // ---- RB1-S3: search_tsv GENERATED + GIN index ------------------------

    @Test
    void search_tsv_is_generated_column() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT attgenerated, atttypid::regtype FROM pg_attribute "
                + "WHERE attrelid = 'agent_registry_mvp'::regclass AND attname = 'search_tsv'");
        assertThat(row.get("attgenerated"))
                .as("search_tsv must be a GENERATED ALWAYS column")
                .asString().isNotBlank();
        assertThat(row.get("atttypid").toString())
                .as("search_tsv must be of type tsvector")
                .contains("tsvector");
    }

    @Test
    void gin_index_on_search_tsv_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'agent_registry_mvp' AND indexname = ?",
                Integer.class, "idx_agent_registry_mvp_search_tsv");
        assertThat(count).isEqualTo(1);
    }

    @Test
    void btree_index_on_tenant_capability_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'agent_registry_mvp' AND indexname = ?",
                Integer.class, "idx_agent_registry_mvp_tenant_capability");
        assertThat(count).isEqualTo(1);
    }

    // ---- RB1-S4: partial index on last_heartbeat WHERE status IN ('ONLINE','DEGRADED') ----

    @Test
    void partial_index_on_last_heartbeat_for_online_and_degraded_rows_exists() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM pg_indexes "
                + "WHERE tablename = 'agent_registry_mvp' AND indexname = ?",
                Integer.class, "ix_agent_registry_mvp_heartbeat_due");
        assertThat(count).isEqualTo(1);

        // pg_get_expr renders the partial-index predicate back to SQL text.
        // PR #389 #4: the partial index now covers both ONLINE and DEGRADED
        // rows so the probe scheduler can re-probe DEGRADED agents and
        // restore them to ONLINE on recovery.
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
        // Insert a valid row first.
        insertValidRow("tenant-A", "agent-001");
        // Try to update to an invalid status — should fail.
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
                    + "tenant_id, agent_id, service_id, agent_name, agent_type, capability, "
                    + "system_profile, route_key, contract_version, capability_version, "
                    + "endpoint_url, weight) "
                    + "VALUES ('tenant-neg', 'agent-neg', 'svc', 'name', 'type', 'cap', "
                    + "'profile', 'rk', '1.0', '1.0', 'http://x', -1)");
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
                .as("HD3-003 defence-in-depth: RLS must be enabled on agent_registry_mvp")
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
        // Insert two rows as the owner (bypasses RLS).
        insertValidRow("tenant-A", "agent-rls-a");
        insertValidRow("tenant-B", "agent-rls-b");

        // Create a restricted role that is bound by RLS.
        jdbc.execute("CREATE ROLE app_role_rls; "
                + "GRANT USAGE ON SCHEMA public TO app_role_rls; "
                + "GRANT SELECT ON agent_registry_mvp TO app_role_rls");

        // Open a connection as the restricted role.
        try (java.sql.Connection restricted = dataSource.getConnection()) {
            // Sanity: the owner connection sees both rows.
            Integer ownerCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_registry_mvp "
                    + "WHERE tenant_id IN ('tenant-A','tenant-B') "
                    + "AND agent_id IN ('agent-rls-a','agent-rls-b')",
                    Integer.class);
            assertThat(ownerCount).isEqualTo(2);

            // Switch the restricted connection's role and set tenant-A.
            // The owner can SET ROLE to a non-owner; RLS then binds.
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
        jdbc.update("INSERT INTO agent_registry_mvp ("
                + "tenant_id, agent_id, service_id, agent_name, agent_type, capability, "
                + "system_profile, route_key, contract_version, capability_version, "
                + "endpoint_url, weight, status) "
                + "VALUES (?, ?, 'svc', 'name', 'type', 'cap', "
                + "'profile', 'rk', '1.0', '1.0', 'http://x', 100, 'ONLINE')",
                tenantId, agentId);
    }
}
