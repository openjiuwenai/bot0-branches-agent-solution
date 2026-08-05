/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — tenant isolation infrastructure
 * (FEAT-016 §3.1; HD3-003).
 *
 * <p>Authority: ADR-0160 + HD3-003 (tenant isolation). This package holds
 * the {@link com.openjiuwen.rdc.tenant.TenantContext} port and its
 * {@link com.openjiuwen.rdc.tenant.ThreadLocalTenantContext} implementation
 * (ThreadLocal-backed). Provides {@code bindForScope(tenantId, work)} for
 * callers (background schedulers, async handlers) that want to explicitly
 * scope tenant state on a worker thread.
 *
 * <h2>Tenant isolation strategy (ESC-2 design pivot)</h2>
 * Tenant isolation is enforced by passing {@code tenantId} as an explicit
 * parameter through every SPI call:
 * <ul>
 *   <li>Discovery SPI takes {@code tenantId} as the first parameter of all
 *       three query methods ({@code searchInstancesByAgentId} /
 *       {@code searchByServiceId} / {@code searchByCapability}) and of
 *       {@code resolveRouteHandle}.</li>
 *   <li>The repository adapter scopes every SQL statement by
 *       {@code WHERE tenant_id = :tenantId} (application-layer hard
 *       isolation, Rule R-C.c).</li>
 *   <li>The repository adapter sets the transaction-scoped
 *       {@code app.tenant_id} so the RLS defence-in-depth policy is bound
 *       for the duration of each call.</li>
 * </ul>
 * Three layers of isolation remain: explicit parameter + application-layer
 * WHERE clause + PostgreSQL RLS policy.
 *
 * <h2>Discovery-time tenant check (S4)</h2>
 * {@code PgMvpDiscoveryServiceImpl} cross-checks the passed
 * {@code tenantId} against {@code TenantContext.current()} only when the
 * context is bound (non-null); unbound scopes skip the cross-check and rely
 * on the explicit-parameter + WHERE-clause + RLS layers.
 *
 * <p>Pure Java at the {@code ThreadLocalTenantContext} level — no Spring /
 * Servlet / JDBC imports (enforced by {@code AgentRdcRegistryJdbcPurityTest}
 * Spring Web / Micrometer / Servlet confinement rules).
 */

package com.openjiuwen.rdc.tenant;
