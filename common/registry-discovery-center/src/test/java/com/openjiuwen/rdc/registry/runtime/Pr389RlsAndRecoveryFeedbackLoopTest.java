/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository;
import com.openjiuwen.rdc.registry.runtime.persistence.jdbc.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.spi.registry.AgentRegistryEntry;
import com.openjiuwen.rdc.spi.registry.InstanceIdCodec;
import com.openjiuwen.rdc.spi.registry.ServiceIdCodec;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import javax.sql.DataSource;

/**
 * Feedback-loop tests for PR #389 review issues #3 and #4 — both are
 * "tests pass under owner connection but break under restricted role" bugs.
 *
 * <h3>#3 — {@code scanDueForProbe} RLS trap</h3>
 * The scan SQL has no {@code withTenant} wrap, so under a restricted
 * ({@code app_role_rls}) connection the RLS policy
 * {@code USING (tenant_id = current_setting('app.tenant_id', true))}
 * evaluates to {@code tenant_id = NULL} → never true → scan returns empty.
 * Productionized on a restricted role, the scheduler silently stops probing.
 * The owner-role IT ({@link AgentRegistryEndToEndIntegrationTest}) never
 * catches this because the owner bypasses RLS.
 *
 * <h3>#4 — DEGRADED is an unrecoverable terminal state</h3>
 * {@code scanDueForProbe} only picks {@code status = 'ONLINE'} rows. Once an
 * agent is downgraded to DEGRADED on a probe failure, it is never re-probed
 * — even if it recovers, it stays DEGRADED until the 15-second visibility
 * window evicts it or a fresh {@code upsert} re-registers it. Flappy
 * networks make this pathological.
 *
 * <p>These tests fail RED against the unfixed code and turn GREEN once:
 * <ul>
 *   <li>#3a — the scan contract test asserts that under a restricted role
 *       with no {@code app.tenant_id} set, scan returns empty (proves the
 *       trap is real, not a hypothetical). This test PASSES on the unfixed
 *       code too — its job is to document the production deployment
 *       requirement: <b>the scheduler scan MUST run on an owner-role
 *       connection, OR be refactored to per-tenant {@code withTenant}
 *       scans</b>. It exists so a future "switch the scheduler to
 *       app_role" change breaks visibly here, not silently in production.</li>
 *   <li>#3b — the scan contract test asserts that under a restricted role
 *       WITH {@code app.tenant_id} set, scan still returns empty (because
 *       the scan SQL has no {@code WHERE tenant_id = :tenantId} parameter
 *       and no {@code withTenant} wrap; even setting {@code app.tenant_id}
 *       cannot help when the adapter never calls {@code set_config}).</li>
 *   <li>#4 — a DEGRADED row whose heartbeat is stale IS picked up by
 *       {@code scanDueForProbe} (the fix: {@code status IN ('ONLINE',
 *       'DEGRADED')}), and a successful probe restores it to ONLINE.</li>
 * </ul>
 *
 * <p>Authority: PR #389 review issues #3 / #4. ADR-0160 decision 4 / 6 +
 * HD3-004 lease/TTL.
 */
class Pr389RlsAndRecoveryFeedbackLoopTest {
    private static final ObjectMapper TEST_MAPPER = new ObjectMapper();

    /**
     * The serviceId that {@link ServiceIdCodec#derive} produces for the
     * sample card's {@code endpointUrl = "https://agent.example/agent"} —
     * host only {@code agent.example} (FEAT-016: serviceId is now host-only,
     * was host-port in REQ-2026-006). Used in every {@code updateStatus} /
     * {@code findEndpoint} call so the key matches the row inserted by
     * {@link #upsertCard}.
     */
    private static final String SERVICE_ID = "agent.example";

    /**
     * The instanceId that {@link InstanceIdCodec#derive} produces for the
     * sample card's {@code endpointUrl = "https://agent.example/agent"} —
     * host {@code agent.example} + https default port {@code 443}. FEAT-016
     * new: the 4th PK column. Used in every {@code updateStatus} /
     * {@code findEndpoint} call so the 4-field PK matches the row.
     */
    private static final String INSTANCE_ID = "agent.example-443";

    private static EmbeddedPostgres pg;
    private static DataSource superuser;
    private static DataSource appRoleSource;
    private static JdbcAgentRegistryRepository appRoleRepo;
    private static JdbcAgentRegistryRepository ownerRepo;

    @BeforeAll
    static void bootPostgresAndRestrictedRole() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        superuser = pg.getPostgresDatabase();
        Flyway.configure().dataSource(superuser).load().migrate();

        // app_role: restricted, RLS-bound. Mirrors the C3Forwarding RLS IT.
        new org.springframework.jdbc.core.JdbcTemplate(superuser).execute(
                "CREATE ROLE app_role; "
                + "GRANT USAGE ON SCHEMA public TO app_role; "
                + "GRANT SELECT, INSERT, UPDATE, DELETE ON agent_registry_mvp TO app_role");

        appRoleSource = new SetRoleDataSource(superuser, "app_role");
        appRoleRepo = new JdbcAgentRegistryRepository(appRoleSource);
        ownerRepo = new JdbcAgentRegistryRepository(superuser);
    }

    @AfterAll
    static void tearDown() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        new org.springframework.jdbc.core.JdbcTemplate(superuser)
                .execute("DELETE FROM agent_registry_mvp");
    }

    // ---- #3: scanDueForProbe RLS trap -------------------------------------

    /**
     * Owner-role scan sees the row. This is the baseline — it's what the
     * existing IT (which uses owner throughout) implicitly relies on.
     */
    @Test
    void owner_role_scan_sees_stale_online_row() {
        upsertCard(ownerRepo, sampleCard("tenant-rls-X", "agent-X"));
        backdateHeartbeat("tenant-rls-X", "agent-X", "10 seconds");

        List<AgentRegistryRepository.ProbeTarget> targets =
                ownerRepo.scanDueForProbe(System.currentTimeMillis(), 100);

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).tenantId()).isEqualTo("tenant-rls-X");
    }

    /**
     * <b>The trap.</b> Under {@code app_role} with no {@code app.tenant_id}
     * set, the RLS policy's {@code tenant_id = current_setting('app.tenant_id',
     * true)} evaluates to {@code tenant_id = NULL} → never true → scan returns
     * empty. This test documents the production deployment requirement: the
     * scheduler scan MUST run on an owner-role connection (or the adapter
     * MUST be refactored to per-tenant {@code withTenant} scans). It PASSES
     * on the unfixed code because the trap is real — its job is to make the
     * assumption explicit and tested, not to verify a fix.
     */
    @Test
    void app_role_scan_no_tenant_set_returns_empty_rls_trap() {
        upsertCard(ownerRepo, sampleCard("tenant-rls-Y", "agent-Y"));
        backdateHeartbeat("tenant-rls-Y", "agent-Y", "10 seconds");

        // app_role + no app.tenant_id set → RLS fail-closed → empty scan.
        List<AgentRegistryRepository.ProbeTarget> targets =
                appRoleRepo.scanDueForProbe(System.currentTimeMillis(), 100);

        assertThat(targets)
                .as("PR #389 #3: app_role scan with no app.tenant_id set returns empty — "
                    + "the scan SQL has no withTenant wrap, so RLS filters everything. "
                    + "Production deployment MUST run the scheduler on an owner-role "
                    + "connection (or refactor to per-tenant withTenant scans).")
                .isEmpty();
    }

    /**
     * Even with {@code app.tenant_id} set as a session-level config on the
     * restricted connection, the scan still returns empty under
     * {@code app_role} — because the adapter never calls
     * {@code set_config('app.tenant_id', ...)} for the scan (no
     * {@code withTenant} wrap). The session-level setting on one pooled
     * connection does not propagate to the connection the adapter actually
     * uses. This proves the trap is not solved by "just set
     * app.tenant_id somewhere"; the scan itself must be tenant-scoped or
     * run on an owner-role connection.
     *
     * @throws Exception if the embedded-postgres JDBC probe fails
     */
    @Test
    void app_role_scan_tenant_set_still_empty_no_with_tenant_wrap() throws Exception {
        upsertCard(ownerRepo, sampleCard("tenant-rls-Z", "agent-Z"));
        backdateHeartbeat("tenant-rls-Z", "agent-Z", "10 seconds");

        // Set app.tenant_id at session level on one app_role connection —
        // confirms the row IS visible to app_role when app.tenant_id is set.
        try (Connection c = appRoleSource.getConnection();
             Statement st = c.createStatement()) {
            st.execute("SET app.tenant_id = 'tenant-rls-Z'");
            var rs = st.executeQuery(
                    "SELECT count(*) FROM agent_registry_mvp "
                    + "WHERE status = 'ONLINE' AND last_heartbeat < NOW() - INTERVAL '5 seconds'");
            rs.next();
            int count = rs.getInt(1);
            assertThat(count)
                    .as("app_role + SET app.tenant_id=Z on the same connection sees the row "
                        + "(proves RLS policy itself works when tenant is set)")
                    .isEqualTo(1);
        }

        // But scanDueForProbe pulls a fresh connection from the pool — no
        // app.tenant_id set, no withTenant wrap → empty.
        List<AgentRegistryRepository.ProbeTarget> targets =
                appRoleRepo.scanDueForProbe(System.currentTimeMillis(), 100);
        assertThat(targets)
                .as("PR #389 #3: scanDueForProbe has no withTenant wrap — even with "
                    + "app.tenant_id settable, the adapter never sets it for the scan. "
                    + "Restricted-role production deployment is broken without an "
                    + "owner-role DataSource for the scheduler, or a per-tenant refactor.")
                .isEmpty();
    }

    // ---- RLS write-path coverage (PR #389 review test gap) ---------------

    /**
     * PR #389 review test gap: all RLS tests in the original suite were
     * SELECT-only. This case proves the adapter's {@code withTenant} wrap
     * scopes UPDATE correctly under a restricted role — an app_role call
     * targeting tenant-A does NOT touch a tenant-B row even when the
     * (tenant_id, agent_id) key collides.
     *
     * <p>Setup: register tenant-B/agent-X as owner (so the row exists).
     * Then call {@code appRoleRepo.updateStatus(new AgentRegistryRepository.StatusUpdate("tenant-A", "agent-X",
     * serviceId, "DEGRADED", false))} — the adapter sets app.tenant_id=A
     * inside the transaction, the UPDATE's WHERE clause is
     * {@code tenant_id='tenant-A' AND agent_id='agent-X' AND service_id=...},
     * which matches 0 rows (tenant-B's row has tenant_id='tenant-B'). The
     * tenant-B row's status is untouched.
     */
    @Test
    void app_role_update_for_tenant_a_does_not_touch_tenant_b_row() {
        upsertCard(ownerRepo, sampleCard("tenant-B", "agent-X"));
        String originalStatus = readStatus("tenant-B", "agent-X");

        // app_role call targeting tenant-A — must not affect tenant-B's row.
        // serviceId is included (REQ-2026-006) but irrelevant here: the
        // tenant_id mismatch in the WHERE clause already excludes tenant-B's row.
        boolean isUpdated = appRoleRepo.updateStatus(new AgentRegistryRepository.StatusUpdate(
                "tenant-A", "agent-X", SERVICE_ID, INSTANCE_ID, "DEGRADED", false));
        assertThat(isUpdated)
                .as("app_role update for tenant-A matched 0 rows (tenant-B's row "
                    + "is invisible under app.tenant_id=A both via RLS and the "
                    + "application-layer WHERE)")
                .isFalse();

        assertThat(readStatus("tenant-B", "agent-X"))
                .as("tenant-B row's status must be untouched by the tenant-A call")
                .isEqualTo(originalStatus);
    }

    /**
     * PR #389 review test gap: cross-tenant DELETE under restricted role.
     * The adapter sets app.tenant_id=A, then DELETE WHERE tenant_id=A AND
     * agent_id=X — matches 0 rows for a tenant-B entry.
     */
    @Test
    void app_role_delete_for_tenant_a_does_not_remove_tenant_b_row() {
        upsertCard(ownerRepo, sampleCard("tenant-B", "agent-Y"));

        boolean isDeleted = appRoleRepo.delete("tenant-A", "agent-Y");
        assertThat(isDeleted)
                .as("app_role delete for tenant-A matched 0 rows")
                .isFalse();

        // tenant-B row still exists.
        assertThat(readStatus("tenant-B", "agent-Y"))
                .as("tenant-B row must still exist after the tenant-A delete attempt")
                .isNotNull();
    }

    /**
     * PR #389 review test gap: cross-tenant INSERT under restricted role.
     * The V2 RLS policy has USING but no WITH CHECK, so INSERTs are not
     * RLS-filtered. The adapter's application-layer isolation comes from
     * the explicit {@code tenantId} parameter — the INSERT always sets
     * tenant_id to the caller's tenantId, never to another tenant. This
     * test documents that contract: an app_role caller passing tenantId=A
     * inserts a tenant-A row (never tenant-B), regardless of any
     * app.tenant_id setting.
     */
    @Test
    void app_role_upsert_inserts_only_into_callers_tenant() {
        upsertCard(appRoleRepo, sampleCard("tenant-A", "agent-Z"));

        // Row landed in tenant-A (the caller's tenantId), not anywhere else.
        assertThat(ownerRepo.findEndpoint("tenant-A", "agent-Z", SERVICE_ID, INSTANCE_ID))
                .as("app_role upsert with tenantId=A must insert into tenant-A")
                .isPresent();
        // Cross-tenant leakage check: no tenant-B row with this agentId.
        assertThat(ownerRepo.findEndpoint("tenant-B", "agent-Z", SERVICE_ID, INSTANCE_ID))
                .as("app_role upsert must NOT leak into tenant-B")
                .isEmpty();
    }

    // ---- #7: upsert preserves DRAINING -----------------------------------

    /**
     * PR #389 #7: re-registering an agent that is in {@code DRAINING} state
     * (operator-initiated graceful drain) MUST NOT pull it back to
     * {@code ONLINE}. Without this, an agent restart during drain re-routes
     * traffic back to the draining entry, defeating the operator's intent.
     *
     * <p>The fix uses a CASE expression in the ON CONFLICT UPDATE:
     * {@code status = CASE WHEN agent_registry_mvp.status = 'DRAINING'
     * THEN 'DRAINING' ELSE 'ONLINE' END}.
     */
    @Test
    void upsert_preserves_draining_status_across_re_registration() {
        // Register, then force into DRAINING (operator-initiated drain).
        upsertCard(ownerRepo, sampleCard("tenant-drain", "agent-drain"));
        ownerRepo.updateStatus(new AgentRegistryRepository.StatusUpdate(
                "tenant-drain", "agent-drain", SERVICE_ID, INSTANCE_ID, "DRAINING", false));
        assertThat(readStatus("tenant-drain", "agent-drain")).isEqualTo("DRAINING");

        // Re-register (upsert) the same agent — DRAINING must be preserved.
        upsertCard(ownerRepo, sampleCard("tenant-drain", "agent-drain"));
        assertThat(readStatus("tenant-drain", "agent-drain"))
                .as("PR #389 #7: DRAINING status preserved across re-registration "
                    + "(operator's drain intent overrides agent restart)")
                .isEqualTo("DRAINING");
    }

    /**
     * Control for {@link #upsert_preserves_draining_status_across_re_registration}:
     * re-registering an agent in any non-DRAINING state (ONLINE / DEGRADED /
     * OFFLINE) resets it to ONLINE — the agent-restart semantic.
     */
    @Test
    void upsert_resets_degraded_to_online_on_re_registration() {
        upsertCard(ownerRepo, sampleCard("tenant-rec2", "agent-rec2"));
        ownerRepo.updateStatus(new AgentRegistryRepository.StatusUpdate(
                "tenant-rec2", "agent-rec2", SERVICE_ID, INSTANCE_ID, "DEGRADED", false));
        assertThat(readStatus("tenant-rec2", "agent-rec2")).isEqualTo("DEGRADED");

        upsertCard(ownerRepo, sampleCard("tenant-rec2", "agent-rec2"));
        assertThat(readStatus("tenant-rec2", "agent-rec2"))
                .as("non-DRAINING states reset to ONLINE on re-registration")
                .isEqualTo("ONLINE");
    }

    // ---- #4: DEGRADED recovery -------------------------------------------

    /**
     * PR #389 #4: once an agent is DEGRADED, the scan MUST still pick it up
     * so a successful probe can restore it to ONLINE. The fix is to widen
     * the scan's status filter from {@code status = 'ONLINE'} to
     * {@code status IN ('ONLINE','DEGRADED')}.
     *
     * <p>RED on the unfixed code (scan returns empty for a DEGRADED row);
     * GREEN once the status filter is widened.
     */
    @Test
    void degraded_row_repicked_by_scan_restored_online_on_probe() {
        // Register, then force-degrade to DEGRADED with a backdated heartbeat.
        upsertCard(ownerRepo, sampleCard("tenant-rec", "agent-rec"));
        ownerRepo.updateStatus(new AgentRegistryRepository.StatusUpdate(
                "tenant-rec", "agent-rec", SERVICE_ID, INSTANCE_ID, "DEGRADED", false));
        backdateHeartbeat("tenant-rec", "agent-rec", "10 seconds");

        // Scan MUST include DEGRADED rows so the probe can retry them.
        List<AgentRegistryRepository.ProbeTarget> targets =
                ownerRepo.scanDueForProbe(System.currentTimeMillis(), 100);
        assertThat(targets)
                .as("PR #389 #4: scanDueForProbe MUST pick up DEGRADED rows so a "
                    + "recovered agent can be restored to ONLINE (was: only ONLINE)")
                .hasSize(1);
        assertThat(targets.get(0).agentId()).isEqualTo("agent-rec");

        // Simulate a successful probe → status restored to ONLINE.
        ownerRepo.updateStatus(new AgentRegistryRepository.StatusUpdate(
                "tenant-rec", "agent-rec", SERVICE_ID, INSTANCE_ID, "ONLINE", true));
        String statusAfterRecovery = readStatus("tenant-rec", "agent-rec");
        assertThat(statusAfterRecovery)
                .as("DEGRADED → ONLINE recovery on successful probe")
                .isEqualTo("ONLINE");
    }

    // ---- helpers ---------------------------------------------------------

    private static void upsertCard(AgentRegistryRepository repo, AgentRegistryEntry card) {
        // FEAT-016: serviceId (host-only) + instanceId (host-port) are both
        // server-derived from endpointUrl. The production push path
        // (MvpRegistryController) and pull path (PullRegistrationBootstrap)
        // both call ServiceIdCodec.applyTo + InstanceIdCodec.applyTo; this
        // test-only helper mirrors that so repo.upsert sees non-null values
        // (the adapter rejects null with IllegalArgumentException).
        ServiceIdCodec.applyTo(card);
        InstanceIdCodec.applyTo(card);
        repo.upsert(card, serializeA2a(card.getA2aAgentCard()).orElse(null));
    }

    private static java.util.Optional<String> serializeA2a(org.a2aproject.sdk.spec.AgentCard card) {
        if (card == null) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(TEST_MAPPER.writeValueAsString(card));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalArgumentException(ex);
        }
    }

    private static AgentRegistryEntry sampleCard(String tenant, String agent) {
        AgentRegistryEntry card = new AgentRegistryEntry();
        card.setTenantId(tenant);
        card.setAgentId(agent);
        card.setAgentName("test-agent");
        card.setFrameworkType(com.openjiuwen.rdc.spi.registry.FrameworkType.JIUWEN);
        card.setRouteKey("rk://svc/default");
        card.setContractVersion("1.0.0");
        card.setCapabilityVersion("2.1.0");
        card.setEndpointUrl("https://agent.example/agent");
        card.setMaxConcurrency(10);
        card.setWeight(100);
        card.setRegion("cn-east-1");
        card.setA2aAgentCard(org.a2aproject.sdk.spec.AgentCard.builder()
                .name("test-agent")
                .description("test probe")
                .version("1.0.0")
                .capabilities(new org.a2aproject.sdk.spec.AgentCapabilities(
                        false, false, false, java.util.List.of()))
                .defaultInputModes(java.util.List.of())
                .defaultOutputModes(java.util.List.of())
                .skills(java.util.List.of())
                .supportedInterfaces(java.util.List.of())
                .build());
        return card;
    }

    private static void backdateHeartbeat(String tenant, String agent, String interval) {
        new org.springframework.jdbc.core.JdbcTemplate(superuser).update(
                "UPDATE agent_registry_mvp SET last_heartbeat = NOW() - INTERVAL '" + interval + "' "
                + "WHERE tenant_id = ? AND agent_id = ?", tenant, agent);
    }

    private static String readStatus(String tenant, String agent) {
        return new org.springframework.jdbc.core.JdbcTemplate(superuser).queryForObject(
                "SELECT status FROM agent_registry_mvp WHERE tenant_id = ? AND agent_id = ?",
                String.class, tenant, agent);
    }

    /**
     * Test-only {@link DataSource} wrapper that runs {@code SET ROLE <role>}
     * on every connection. Mirrors the C3Forwarding RLS IT pattern.
     */
    private static final class SetRoleDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final String role;

        SetRoleDataSource(DataSource delegate, String role) {
            this.delegate = delegate;
            this.role = role;
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection c = delegate.getConnection();
            try (Statement st = c.createStatement()) {
                st.execute("SET ROLE " + role);
            }
            return c;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            Connection c = delegate.getConnection(username, password);
            try (Statement st = c.createStatement()) {
                st.execute("SET ROLE " + role);
            }
            return c;
        }
    }
}
