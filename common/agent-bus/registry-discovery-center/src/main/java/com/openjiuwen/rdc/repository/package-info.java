/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — data access layer (FEAT-016 §3.1).
 *
 * <p>This is the <b>only</b> package under {@code com.openjiuwen.rdc} allowed
 * to import {@code java.sql} / {@code javax.sql} /
 * {@code org.springframework.jdbc..} / {@code org.springframework.transaction..}.
 * The {@code controller} / {@code service} / {@code health} / {@code tenant}
 * packages call {@link com.openjiuwen.rdc.repository.AgentRegistryRepository}
 * and never touch JDBC directly — enforced at test time by
 * {@code AgentRdcRegistryJdbcPurityTest} and at edit time by the
 * {@code req-2026-003-jdbc-confined-to-persistence} regex-check gate rule.
 *
 * <p>Authority: ADR-0160 decision 4 + HD3-001/003/004/005/006.
 *
 * <h2>Stage 24 RLS wiring</h2>
 * Every method on {@link com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository}
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
 * introduce a Consul-backed implementation under a separate package after an
 * ADR opens the ArchUnit exemption. Enforced by
 * {@code req-2026-003-consul-forbidden-mvp} regex-check gate rule and the
 * {@code AgentRdcRegistryJdbcPurityTest} Consul-forbidden assertion.
 */

package com.openjiuwen.rdc.repository;
