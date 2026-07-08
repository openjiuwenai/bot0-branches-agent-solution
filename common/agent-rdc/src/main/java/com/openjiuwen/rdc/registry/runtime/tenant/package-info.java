/**
 * agent-bus registry runtime — tenant isolation infrastructure (HD3-003).
 *
 * <p>Authority: ADR-0160 + HD3-003 (tenant isolation). This package holds
 * {@link com.openjiuwen.rdc.registry.runtime.tenant.ThreadLocalTenantContext}
 * — implements the SPI {@link com.openjiuwen.rdc.spi.registry.TenantContext}
 * port using a {@link ThreadLocal}. Provides {@code bindForScope(tenantId, work)}
 * for callers (background schedulers, async handlers) that want to explicitly
 * scope tenant state on a worker thread.
 *
 * <h2>Tenant isolation strategy (ESC-2 design pivot)</h2>
 * The original step-5 delivery projection planned a Servlet {@code Filter}
 * that read {@code X-Tenant-Id} from the request header and populated the
 * {@link ThreadLocal} on every request entry. That approach requires
 * {@code jakarta.servlet-api} + {@code spring-web} on the agent-bus compile
 * classpath, but agent-bus's tenant subpackage must stay pure Java so
 * background schedulers / async handlers can use
 * {@code ThreadLocalTenantContext} without pulling Spring Web onto the
 * classpath (the rest of agent-bus ships {@code spring-boot-starter-web} at
 * compile scope per PR #389 review issue #6). Per user decision (ESC-2), the
 * {@code TenantFilter} + {@code TenantFilterRegistration} classes are
 * dropped from this REQ; tenant isolation is instead enforced by passing
 * {@code tenantId} as an explicit parameter through every SPI call:
 * <ul>
 *   <li>Discovery SPI takes {@code tenantId} as the first parameter of both
 *       {@code discoverBestAgents} overloads and {@code resolveRouteHandle}.</li>
 *   <li>The repository adapter scopes every SQL statement by
 *       {@code WHERE tenant_id = :tenantId} (application-layer hard isolation,
 *       Rule R-C.c).</li>
 *   <li>The repository adapter sets the transaction-scoped
 *       {@code app.tenant_id} so the §RLS defence-in-depth policy is bound
 *       for the duration of each call.</li>
 * </ul>
 * Three layers of isolation remain: explicit parameter + application-layer
 * WHERE clause + PostgreSQL RLS policy. The {@code TenantContext} SPI port
 * stays (phase-2 reactor-context implementation may use it); the
 * {@code ThreadLocalTenantContext} impl stays for callers that want explicit
 * scope binding (e.g. background schedulers running without an HTTP request).
 *
 * <h2>Discovery-time tenant check (S4)</h2>
 * {@code PgMvpDiscoveryServiceImpl} (S4) cross-checks the passed
 * {@code tenantId} against {@code TenantContext.current()} only when the
 * context is bound (non-null); unbound scopes (no caller bound the
 * ThreadLocal) skip the cross-check and rely on the explicit-parameter +
 * WHERE-clause + RLS layers. This avoids the Filter dependency while
 * preserving the defence-in-depth semantics for callers that opt in to
 * explicit scope binding.
 *
 * <p>Pure Java at the {@code ThreadLocalTenantContext} level — no Spring /
 * Servlet / JDBC imports.
 */
package com.openjiuwen.rdc.registry.runtime.tenant;
