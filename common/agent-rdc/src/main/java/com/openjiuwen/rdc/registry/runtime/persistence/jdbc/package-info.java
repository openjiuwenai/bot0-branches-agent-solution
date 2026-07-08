/**
 * agent-bus registry runtime — persistence JDBC adapter (ADR-0160 decision 4).
 *
 * <p>This is the <b>only</b> subpackage under
 * {@code com.openjiuwen.rdc.registry.**} allowed to import {@code java.sql}
 * / {@code javax.sql} / {@code org.springframework.jdbc.*} /
 * {@code javax.sql.DataSource}. The {@code api} / {@code discovery} /
 * {@code health} subpackages call {@link com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository}
 * and never touch JDBC directly — enforced at test time by
 * {@code AgentRdcRegistryJdbcPurityTest} (S5) and at edit time by the
 * {@code req-2026-003-jdbc-confined-to-persistence} regex-check gate rule.
 *
 * <p>Authority: ADR-0160 decision 4 + HD3-001/003/004/005/006. The design
 * doc's {@code PgMvpDiscoveryServiceImpl} code (which used {@code JdbcTemplate}
 * directly in {@code runtime.discovery}) is refactored: JdbcTemplate usage
 * 下沉 to {@link com.openjiuwen.rdc.registry.runtime.persistence.jdbc.JdbcAgentRegistryRepository},
 * {@code PgMvpDiscoveryServiceImpl} (S4) becomes a thin orchestrator calling
 * this port + encoding route handles via {@code RouteHandleCodec}.
 *
 * <h2>Stage 24 RLS wiring</h2>
 * Every method on {@link com.openjiuwen.rdc.registry.runtime.persistence.jdbc.JdbcAgentRegistryRepository}
 * runs inside a short transaction that sets the transaction-scoped
 * {@code app.tenant_id} (PostgreSQL {@code set_config('app.tenant_id',
 * :tenantId, true)} ≡ {@code SET LOCAL}) so a restricted (non-owner)
 * connection is filtered by tenant. Application-layer
 * {@code WHERE tenant_id = :tenantId} remains the primary isolation
 * (Rule R-C.c); RLS is the defence-in-depth fallback. The table owner
 * (superuser / Flyway migration) bypasses RLS, so superuser-backed
 * integration tests are unaffected.
 *
 * <h2>MVP Consul-forbidden trip-wire</h2>
 * This package MUST NOT import {@code com.ecwid.consul} or any Consul client
 * type. The MVP runs on single PostgreSQL with tsvector+GIN; phase 2 will
 * introduce a Consul-backed implementation under a separate subpackage
 * after an ADR opens the ArchUnit exemption. Enforced by
 * {@code req-2026-003-consul-forbidden-mvp} regex-check gate rule and the
 * {@code AgentRdcRegistryJdbcPurityTest} Consul-forbidden assertion.
 */
package com.openjiuwen.rdc.registry.runtime.persistence.jdbc;
