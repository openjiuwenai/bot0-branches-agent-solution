/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — Spring configuration &amp; wiring
 * (FEAT-016 §3.1).
 *
 * <p>Holds the five cross-cutting configuration / operational-context types
 * previously at the {@code registry.runtime} root:
 * <ul>
 *   <li>{@link com.openjiuwen.rdc.config.RegistryRuntimeBeanConfig} —
 *       {@code DataSource} / {@code JdbcTemplate} / {@code Flyway} /
 *       {@code ObjectMapper} / {@code JdbcAgentRegistryRepository} bean
 *       wiring.</li>
 *   <li>{@link com.openjiuwen.rdc.config.RegistryObservabilityConfig} —
 *       Micrometer facade.</li>
 *   <li>{@link com.openjiuwen.rdc.config.RegistrySchedulingConfig} —
 *       {@code @EnableScheduling} anchor for the probe scheduler.</li>
 *   <li>{@link com.openjiuwen.rdc.config.OpenApiConfig} — OpenAPI surface.</li>
 *   <li>{@link com.openjiuwen.rdc.config.RegistryOpContext} — operational
 *       context holder.</li>
 * </ul>
 *
 * <p>Licensed for {@code javax.sql.DataSource} (bean construction),
 * {@code io.micrometer..} (observability facade), and Jackson
 * ({@code ObjectMapper} bean). JDBC proper ({@code java.sql..},
 * {@code org.springframework.jdbc..}, {@code org.springframework.transaction..})
 * is forbidden here — it lives in {@code repository..} only, enforced by
 * {@code AgentRdcRegistryJdbcPurityTest}.
 *
 * <p>Authority: ADR-0160 + FEAT-016 §3.1.
 */

package com.openjiuwen.rdc.config;
