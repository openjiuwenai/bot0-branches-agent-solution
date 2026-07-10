/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * Background health-probe scheduler for the agent registry MVP (ADR-0160 +
 * HD3-004 lease/TTL).
 *
 * <p>{@link com.openjiuwen.rdc.registry.runtime.health.MvpHealthProbeScheduler}
 * runs a {@code @Scheduled} sweep every 5 seconds (configurable via
 * {@code agent-bus.registry.mvp.probe-interval-ms}), calling
 * {@link com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository#scanDueForProbe}
 * to find {@code ONLINE} entries whose {@code last_heartbeat} is stale,
 * HTTP-probing each target's {@code /health} endpoint via
 * Spring {@code RestClient}. 2xx → reaffirm {@code ONLINE} + refresh
 * heartbeat; 5xx / connect exception → downgrade to {@code DEGRADED}. The
 * 15-second visibility window in the discovery SQL eventually filters out
 * entries whose heartbeat stays stale (HD3-004).
 *
 * <p>Each probe is wrapped in
 * {@link com.openjiuwen.rdc.registry.runtime.tenant.ThreadLocalTenantContext#bindForScope}
 * so the Stage 24 RLS wiring in {@code JdbcAgentRegistryRepository} sees the
 * correct tenant for the {@code updateStatus} call (ESC-2 design pivot,
 * ADR-0160 decision 6 — background scheduling paths bind tenant scope
 * explicitly, since no {@code TenantFilter} populates it at request entry).
 *
 * <p>Spring Web ({@code RestClient}) + Spring Context ({@code @Component} /
 * {@code @Scheduled}) are visible via {@code spring-boot-starter-web} at
 * {@code provided} scope (ADR-0160 decision 7). JDBC is forbidden in this
 * subpackage — the scheduler calls {@code AgentRegistryRepository} only.
 *
 * <p>Authority: ADR-0160 + HD3-004 + Rule R-C.c (Stage 24 RLS wiring).
 */
package com.openjiuwen.rdc.registry.runtime.health;
