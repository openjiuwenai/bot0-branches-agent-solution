/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

/**
 * agent-bus registry-discovery-center — Spring configuration &amp; wiring
 * (FEAT-016 §3.1).
 *
 * <p>Holds cross-cutting configuration / operational-context types:
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
 *   <li>{@link com.openjiuwen.rdc.config.RegistryJacksonConfig} /
 *       {@link com.openjiuwen.rdc.config.RegistryObjectMapper} /
 *       {@link com.openjiuwen.rdc.config.DiscoveryCandidateJacksonMixin} —
 *       discovery JSON serialization.</li>
 *   <li>{@link com.openjiuwen.rdc.config.RegistrationPathGuard} — pull vs
 *       deployment-discovery mutual exclusion.</li>
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
