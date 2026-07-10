/*
 * Copyright (C) 2026 Huawei Technologies Co., Ltd.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.openjiuwen.rdc.spi.registry;

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
 * @since 2026-07-10
 */
public interface TenantContext {
    /**
     * Returns the tenant id bound to the current call scope.
     *
     * @return the tenant id bound to the current call scope, or {@code null}
     *         when no caller has bound the context. HTTP-entry call sites
     *         pass {@code tenantId} explicitly and leave the context unbound;
     *         background scheduling paths bind via
     *         {@code ThreadLocalTenantContext.bindForScope} for the duration
     *         of a unit of work. Discovery callers cross-check the bound
     *         tenant against the explicit parameter only when bound (ADR-0160
     *         decision 6).
     */
    String current();
}
