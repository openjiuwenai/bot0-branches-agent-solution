/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.tenant;

/**
 * Read-only access to the tenant identifier of the current call scope.
 *
 * <p>Authority: ADR-0160 (Stage 4 Registry SPI Runtime Promotion) + HD3-003
 * (tenant isolation). The MVP implementation
 * {@code ThreadLocalTenantContext} (in {@code registry.runtime.tenant}) is
 * populated by background-scheduling callers via
 * {@code bindForScope(tenantId, work)} (ADR-0160 decision 6 / ESC-2 design
 * pivot — the deprecated {@code TenantFilter} request-header approach was
 * dropped in favour of explicit-parameter + application-layer WHERE + RLS).
 * Phase 2 may swap in a reactor-context-backed implementation without
 * touching this port.
 *
 * <p>Pure Java — no Spring / JDBC / Jackson / Consul imports (ADR-0160
 * decision 1).
 *
 * @since 0.1.0 (2026)
  */
public interface TenantContext {

    /**
     * @return the tenant id bound to the current call scope, or {@code null}
     *         when no caller has bound the context. HTTP-entry call sites
     *         pass {@code tenantId} explicitly and leave the context unbound;
     *         background scheduling paths bind via
     *         {@code ThreadLocalTenantContext.bindForScope} for the duration
     *         of a unit of work. Discovery callers cross-check the bound
     *         tenant against the explicit parameter only when bound (ADR-0160
         *   decision 6).
     */
    String current();
}
