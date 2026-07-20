/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry discovery SPI — runtime route index + opaque route handle
 * contract surface owned by the Bus &amp; State Hub plane.
 *
 * <p>Authority: ADR-0160 (Stage 4 Registry SPI Runtime Promotion). The dual-method
 * {@link com.openjiuwen.rdc.spi.registry.AgentDiscoveryService} contract,
 * the unified {@link com.openjiuwen.rdc.model.AgentCardDto} with
 * {@code @Nullable} business definition fields, the
 * {@link com.openjiuwen.rdc.model.RouteResolution} returned by
 * {@code resolveRouteHandle}, and the
 * {@link com.openjiuwen.rdc.spi.registry.TenantContext} port all live here so
 * that callers (Orchestrator / Gateway / forwarding layer) depend on no
 * implementation detail — only on Java types.
 *
 * <p>SPI-pure per ADR-0160 decision 1 / CLAUDE.md Rule R-D sub-clause .d:
 * imports restricted to {@code java.*} + same-spi-package siblings. Spring,
 * JDBC, Jackson, Consul, Servlet, and Netty are all forbidden — enforced by
 * the {@code req-2026-003-spi-registry-no-spring/-no-jdbc/-no-jackson/
 * -no-consul} post-edit gate rules and, at test time, by
 * {@code AgentRdcRegistrySpiPurityTest}. The MVP implementation
 * ({@code PgMvpDiscoveryServiceImpl}) lives in
 * {@code com.openjiuwen.rdc.registry.runtime.discovery} and is selected
 * via {@code @Primary}; the phase-2 Consul implementation swaps in without
 * touching this package.
 *
 * <p>{@link com.openjiuwen.rdc.model.Nullable} is a local marker
 * annotation defined in this package so the SPI stays self-contained — no
 * {@code jakarta.annotation} / {@code org.springframework.lang} dependency
 * is pulled across the SPI boundary.
 */
package com.openjiuwen.rdc.spi.registry;
