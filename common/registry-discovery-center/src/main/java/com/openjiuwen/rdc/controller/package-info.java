/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — MVC Controller layer, HTTP entry
 * point (FEAT-016 §3.1).
 *
 * <p>{@link com.openjiuwen.rdc.controller.MvpRegistryController} exposes
 * {@code POST /api/registry/register}, three discovery query dimensions
 * ({@code GET /api/registry/instances/{tenantId}/{agentId}},
 * {@code GET /api/registry/instances/by-service/{tenantId}/{serviceId}},
 * {@code GET /api/registry/instances/by-capability/{tenantId}/{capability}}),
 * {@code POST /api/registry/route-handle/resolve} (FEAT-016 forwarding-layer
 * handle resolution), and three {@code DELETE /api/registry/deregister/...}
 * variants (1-field {@code {tenantId}/{agentId}} / 2-field
 * {@code {tenantId}/{agentId}/{serviceId}} / 4-field
 * {@code {tenantId}/{agentId}/{serviceId}/{instanceId}}). All endpoints read
 * {@code tenantId} from the path / body and pass it down explicitly — no
 * {@code TenantFilter} populates a {@code TenantContext} at Servlet entry
 * (ESC-2 design pivot, ADR-0160 decision 6: three-layer tenant isolation —
 * explicit parameter + application-layer {@code WHERE tenant_id = :tenantId}
 * + PostgreSQL RLS).
 *
 * <p>Spring Web annotations are visible at compile time via
 * {@code spring-boot-starter-web}; Jackson is licensed here for A2A AgentCard
 * JSON serialization at the HTTP boundary (REQ-2026-001). JDBC is forbidden
 * in this package — the controller calls
 * {@link com.openjiuwen.rdc.repository.AgentRegistryRepository} /
 * {@link com.openjiuwen.rdc.service.AgentDiscoveryService} only.
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + FEAT-016 §6.1.
 */
package com.openjiuwen.rdc.controller;
