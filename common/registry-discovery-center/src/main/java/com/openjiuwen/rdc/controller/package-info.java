/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — MVC Controller layer, HTTP entry
 * point (FEAT-016 + Feat-015).
 *
 * <p>{@link com.openjiuwen.rdc.controller.MvpRegistryController} exposes
 * Feat-015 {@code POST /api/registry/discover}, push
 * {@code POST /api/registry/register} (returns 410 when deployment-discovery
 * is enabled), and deregister variants.
 * {@link com.openjiuwen.rdc.controller.InstanceRouteController} exposes
 * FEAT-016 instance list + {@code POST /route-handle/resolve}.
 *
 * <p>All endpoints take {@code tenantId} explicitly — no {@code TenantFilter}
 * (ADR-0160 decision 6). JDBC is forbidden; controllers call
 * {@link com.openjiuwen.rdc.repository.AgentRegistryRepository} /
 * {@link com.openjiuwen.rdc.service.AgentDiscoveryService} only.
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + Feat-015 / FEAT-016.
 */
package com.openjiuwen.rdc.controller;
