/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — business logic layer (FEAT-016 §3.1).
 *
 * <p>Hosts the {@link com.openjiuwen.rdc.service.AgentDiscoveryService} SPI
 * contract — three discovery query dimensions
 * ({@code searchInstancesByAgentId} / {@code searchByServiceId} /
 * {@code searchByCapability}, all accepting a nullable
 * {@code contractVersion} filter) plus opaque-handle resolution
 * ({@code resolveRouteHandle}) — its MVP implementation
 * {@link com.openjiuwen.rdc.service.PgMvpDiscoveryServiceImpl}, and the
 * opaque route-handle codec
 * {@link com.openjiuwen.rdc.service.RouteHandleCodec}.
 *
 * <p>Per FEAT-016 §3.1 the SPI interface and its implementation share the
 * same {@code service} package — the previous {@code spi.registry} /
 * {@code registry.runtime.discovery} physical split is folded. The phase-2
 * Consul-backed implementation will replace {@code PgMvpDiscoveryServiceImpl}
 * in-place without touching the {@code AgentDiscoveryService} contract.
 *
 * <p>{@link com.openjiuwen.rdc.service.RouteHandleCodec} encodes the opaque
 * route handle ({@code v2:} + base64 JSON of 6 fields) using Jackson; it
 * does NOT need package-private access to {@code AgentRegistryEntry} (only
 * public getters), so it lives here rather than in {@code model}. Only
 * {@code PgMvpDiscoveryServiceImpl} (and the forwarding-layer path through
 * {@code resolveRouteHandle}) uses it. The {@code InstanceIdCodec} /
 * {@code ServiceIdCodec} siblings live in {@code model} because they mediate
 * package-private setters. Jackson is licensed to this package per the
 * {@code AgentRdcRegistryJdbcPurityTest} Jackson-confinement rule.
 *
 * <p>Authority: ADR-0160 decision 1 / 4 + FEAT-016 §2.3.1 / §3.1.
 */

package com.openjiuwen.rdc.service;
