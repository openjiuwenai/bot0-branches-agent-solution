/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * HTTP entry point for the agent registry MVP (ADR-0160 decisions 4 / 6 / 7).
 *
 * <p>{@link com.openjiuwen.rdc.registry.runtime.api.MvpRegistryController}
 * exposes {@code POST /api/registry/register} (upsert an
 * {@link com.openjiuwen.rdc.spi.registry.AgentCard}) and
 * {@code DELETE /api/registry/deregister} (remove by {@code tenantId + agentId}).
 * Both endpoints read {@code tenantId} from the request body / request param
 * and pass it down explicitly — no {@code TenantFilter} populates a
 * {@code TenantContext} at Servlet entry (ESC-2 design pivot, ADR-0160
 * decision 6: three-layer tenant isolation — explicit parameter +
 * application-layer {@code WHERE tenant_id = :tenantId} + PostgreSQL RLS).
 *
 * <p>Spring Web annotations ({@code @RestController} / {@code @RequestMapping}
 * / {@code @PostMapping} / {@code @DeleteMapping}) are visible at compile
 * time via {@code spring-boot-starter-web} at {@code provided} scope
 * (ADR-0160 decision 7, ESC-2(b) option B); the runtime consumer
 * (agent-runtime) ships starter-web. JDBC is forbidden in this subpackage —
 * the controller calls
 * {@link com.openjiuwen.rdc.repository.AgentRegistryRepository}
 * (port in {@code runtime.persistence.jdbc}) only.
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + HD3-001 / 002 / 003.
 */
package com.openjiuwen.rdc.registry.runtime.api;
