/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.card.AgentCardFetcher;
import com.openjiuwen.rdc.config.RegistryObjectMapper;
import com.openjiuwen.rdc.repository.AgentRegistryRepository;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.security.CallerAuthorizationPolicy;
import com.openjiuwen.rdc.security.RdcCardFetchOptions;
import com.openjiuwen.rdc.security.RegistrySecurityProperties;
import com.openjiuwen.rdc.tenant.TenantContext;
import com.openjiuwen.rdc.tenant.ThreadLocalTenantContext;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Runtime bean wiring for the agent-rdc standalone Spring Boot application
 * (ADR-0160 decision 7 — agent-rdc ships as a runnable application, not a
 * library jar).
 *
 * <p>Provides the beans that {@code main()} startup needs but that no
 * existing {@code @Configuration} class declares:
 * <ul>
 *   <li>{@link MeterRegistry} — required by {@link RegistryObservabilityConfig}'s
 *       constructor. The application does not pull in
 *       {@code spring-boot-starter-actuator} (VR-2 keeps the dependency list
 *       frozen), so we register a {@link SimpleMeterRegistry} here. Production
 *       deployments that want Prometheus exposition can swap this bean for a
 *       {@code PrometheusMeterRegistry} via a future REQ without touching
 *       existing Java sources (VR-7).</li>
 *   <li>{@link AgentRegistryRepository} — required by
 *       {@code MvpRegistryController}. {@link JdbcAgentRegistryRepository} is
 *       a {@code final} class with no stereotype annotation by design (the
 *       JDBC-purity test confines it to the persistence.jdbc subpackage and
 *       keeps it free of Spring stereotype imports), so it cannot self-register
 *       via component scan. We instantiate it here against the auto-configured
 *       {@link DataSource}.</li>
 *   <li>{@link ObjectMapper} ({@code com.fasterxml.jackson.databind}) — required
 *       by {@code MvpRegistryController} for serialising the A2A AgentCard to
 *       jsonb. Spring Boot 4 ships Jackson 3 by default
 *       ({@code tools.jackson.databind.ObjectMapper}); the controller still
 *       targets the Jackson 2 API that ships transitively via
 *       {@code spring-boot-starter-web}, so we register a Jackson 2
 *       {@link ObjectMapper} bean here. Migrating the controller to Jackson 3
 *       is a separate concern.</li>
 *   <li>{@link Flyway} — Spring Boot 4 removed {@code FlywayAutoConfiguration}
 *       and the {@code spring-boot-starter-flyway} starter from the core
 *       distribution (the autoconfigure jar ships no Flyway classes, and no
 *       {@code FlywayMigrationInitializer} is available either). The
 *       {@code spring.flyway.*} properties in {@code application.yml} are
 *       therefore inert at runtime. We construct {@link Flyway} manually here
 *       against the auto-configured {@link DataSource} and let Spring invoke
 *       {@link Flyway#migrate()} via {@code @Bean(initMethod = "migrate")}.
 *       {@link #agentRegistryRepository} declares {@link Flyway} as a parameter
 *       to guarantee migrations run before the repository (and therefore
 *       before the {@code @Scheduled} probe sweep) issues its first query.</li>
 * </ul>
 *
 * <p>This class is a <em>new</em> artifact under VR-7 — it adds wiring without
 * modifying any existing Java source. Existing tests construct
 * {@code JdbcAgentRegistryRepository} and {@code RegistryObservabilityConfig}
 * directly via their public constructors, so this bean wiring does not affect
 * them.
 *
 * <p>Authority: ADR-0160 decision 7 + REQ-2026-002 VR-2 / VR-7.
 *
 * @since 0.1.0
 */
@Configuration
public class RegistryRuntimeBeanConfig {
    /**
     * In-process {@link MeterRegistry} for the standalone runtime. Suffices
     * for audit + Counter/Timer emission via {@link RegistryObservabilityConfig};
     * scrapable exposition is a deploy-time concern.
     *
     * @return a non-null {@link SimpleMeterRegistry}
     */
    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    /**
     * Flyway migrator for the {@code agent_registry_mvp} schema. Spring Boot 4
     * dropped Flyway auto-configuration, so we build the {@link Flyway}
     * instance manually from the same {@code spring.flyway.*} inputs the
     * autoconfig would have consumed. {@code initMethod = "migrate"} makes
     * Spring run migrations as part of bean initialisation.
     *
     * <p>{@code baselineOnMigrate(true)} is safe here — the database is
     * expected to start empty; if a deploy ever finds pre-existing tables
     * without a {@code flyway_schema_history} row, Flyway will baseline at
     * version 0 and apply V2+ on top.
     *
     * @param dataSource the auto-configured PG {@link DataSource}
     * @return a configured {@link Flyway} instance
     */
    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
    }

    /**
     * JDBC-backed {@link AgentRegistryRepository} bound to the auto-configured
     * {@link DataSource}. The {@link Flyway} parameter is intentionally
     * unused — it exists only to make Spring instantiate the {@link Flyway}
     * bean (and therefore run migrations) before this repository, so the
     * {@code @Scheduled} probe sweep and the first HTTP register request
     * always see the {@code agent_registry_mvp} table.
     *
     * @param dataSource the auto-configured PG {@link DataSource}
     * @param flyway     ensures migrations run first; otherwise unused
     * @return a new {@link JdbcAgentRegistryRepository}
     */
    @Bean
    public AgentRegistryRepository agentRegistryRepository(DataSource dataSource, Flyway flyway) {
        return new JdbcAgentRegistryRepository(dataSource);
    }

    /**
     * Jackson 2 {@link ObjectMapper} for {@code MvpRegistryController}'s A2A
     * AgentCard serialisation. Spring Boot 4 auto-configures Jackson 3
     * ({@code tools.jackson.databind.ObjectMapper}) instead; this bean bridges
     * the gap until the controller migrates.
     *
     * @return a default {@link ObjectMapper}
     */
    @Bean
    public ObjectMapper objectMapper() {
        return RegistryObjectMapper.createJackson2();
    }

    /**
     * agentCardFetcher.
     *
     * @param cardFetchOptions cardFetchOptions
     * @return result
     * @since 0.1.0
     */
    @Bean
    public AgentCardFetcher agentCardFetcher(RdcCardFetchOptions cardFetchOptions) {
        return AgentCardFetcher.fromSecurity(cardFetchOptions);
    }

    /**
     * callerAuthorizationPolicy.
     *
     * @param securityProperties securityProperties
     * @return result
     * @since 0.1.0
     */
    @Bean
    public CallerAuthorizationPolicy callerAuthorizationPolicy(RegistrySecurityProperties securityProperties) {
        if (securityProperties.isAllowlistConfigured()) {
            return new CallerAuthorizationPolicy.Allowlist(securityProperties);
        }
        return new CallerAuthorizationPolicy.Permissive();
    }

    /**
     * ThreadLocal-backed {@link TenantContext} used by
     * {@code PgMvpDiscoveryServiceImpl} for the optional cross-check between
     * an explicitly-passed {@code tenantId} and any tenant bound to the call
     * scope. HTTP-entry call sites leave the context unbound; background
     * scheduling paths bind via {@link ThreadLocalTenantContext#bindForScope}.
     *
     * @return a new {@link ThreadLocalTenantContext}
     */
    @Bean
    public TenantContext tenantContext() {
        return new ThreadLocalTenantContext();
    }
}
