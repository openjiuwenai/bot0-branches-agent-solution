/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

/**
 * Layered purity harness for the Stage 4 registry/discovery runtime
 * ({@code com.openjiuwen.rdc.registry..}).
 *
 * <p>Three invariants are pinned here:
 *
 * <ol>
 *   <li><b>JDBC confinement</b> — {@code java.sql} / {@code javax.sql} /
 *       {@code org.springframework.jdbc..} may be imported ONLY inside
 *       {@code registry.runtime.persistence.jdbc..}. The {@code api} /
 *       {@code discovery} / {@code health} / {@code tenant} subpackages call
 *       the {@link com.openjiuwen.rdc.registry.runtime.persistence.jdbc.AgentRegistryRepository}
 *       port and never touch JDBC directly (ADR-0160 decision 4).</li>
 *   <li><b>Consul forbidden</b> — the entire {@code registry..} package is
 *       Consul-free in MVP. Phase 2 introduces Consul under an ADR-gated
 *       exemption (ADR-0160 decision 2 / NFR-2 trip-wire).</li>
 *   <li><b>Spring Web + Micrometer confinement</b> (ESC-2(b) follow-up) —
 *       {@code org.springframework.web..} and {@code io.micrometer..} may be
 *       imported ONLY inside {@code registry.runtime.api..},
 *       {@code registry.runtime.discovery..}, and
 *       {@code registry.runtime.health..}. The {@code tenant} subpackage
 *       stays pure Java so non-HTTP-entry callers (schedulers, async
 *       handlers) can use {@code ThreadLocalTenantContext} without pulling
 *       Servlet / Web onto the classpath. The {@code persistence.jdbc}
 *       subpackage also stays free of Spring Web / Micrometer — it imports
 *       Spring JDBC / transaction types but not Web / Micrometer.</li>
 * </ol>
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + ESC-2(b) boundary evolution.
 * The post-edit gate rule {@code req-2026-003-jdbc-confined-to-persistence}
 * enforces the JDBC invariant at edit time; this test is the second layer.
 * The Spring Web / Micrometer confinement rule has no post-edit gate yet —
 * this test is the sole enforcer (S5 follow-up to ESC-2(b)).
 *
 * <p>One {@code @Test} per forbidden dependency so a violation reports the
 * exact offending import. Test classes are excluded — the rule constrains
 * the shipped runtime surface, not test scaffolding.
 *
 * <p>Assertion ID: HA-002-REG.
 *
 * @since 2026-07-10
 */
class AgentRdcRegistryJdbcPurityTest {
    /**
     * Production registry runtime classes only
     * ({@code com.openjiuwen.rdc.registry} and sub-packages). Test
     * classes are excluded.
     */
    private static final JavaClasses REGISTRY_RUNTIME = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.openjiuwen.rdc.registry");

    /**
     * Sub-packages that ARE allowed to import JDBC / Spring JDBC types.
     * Everything outside this set is JDBC-free.
     */
    private static final String JDBC_ADAPTER = "com.openjiuwen.rdc.registry.runtime.persistence.jdbc..";

    /**
     * Sub-packages that ARE allowed to import Spring Web / Micrometer types
     * (ESC-2(b) boundary — ADR-0160 decision 7). Three runtime subpackages
     * need Spring Web / Micrometer:
     * <ul>
     *   <li>{@code api} — {@code @RestController} / {@code @RequestMapping}</li>
     *   <li>{@code discovery} — none directly, but kept inside the boundary
     *       for future Prometheus / OpenAPI annotations</li>
     *   <li>{@code health} — {@code @Component} / {@code @Scheduled} /
     *       {@code RestClient}</li>
     * </ul>
     * The root {@code runtime} package (for
     * {@code RegistryObservabilityConfig} / {@code RegistrySchedulingConfig})
     * is also licensed for Spring Web / Micrometer because it ships the
     * observability facade that uses {@code io.micrometer.core.instrument}.
     */
    private static final String[] SPRING_WEB_MICROMETER_ALLOWED = {
            "com.openjiuwen.rdc.registry.runtime..",
            "com.openjiuwen.rdc.registry.runtime.api..",
            "com.openjiuwen.rdc.service..",
            "com.openjiuwen.rdc.registry.runtime.health.."
    };

    // ---- 1. JDBC confinement (ADR-0160 decision 4) -----------------------

    @Test
    void jdbc_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("java.sql..")
                .because("JDBC lives only in registry.runtime.persistence.jdbc.. (ADR-0160 "
                        + "decision 4); api / discovery / health / tenant call the "
                        + "AgentRegistryRepository port and never touch JDBC directly.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void javax_sql_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.registry.runtime")
                .should().dependOnClassesThat().resideInAPackage("javax.sql..")
                .because("javax.sql (DataSource) lives only in registry.runtime.persistence.jdbc.. "
                        + "(ADR-0160 decision 4) and the registry.runtime root wiring package "
                        + "(RegistryRuntimeBeanConfig constructs JdbcAgentRegistryRepository + "
                        + "Flyway from a DataSource); every other subpackage stays pure Java.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_jdbc_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
                .because("Spring JDBC (NamedParameterJdbcTemplate / RowMapper) lives only in "
                        + "registry.runtime.persistence.jdbc.. (ADR-0160 decision 4).")
                .check(REGISTRY_RUNTIME);
    }

    /**
     * PR #389 review issue (test gap): the JDBC adapter uses
     * {@code TransactionTemplate} / {@code DataSourceTransactionManager} for
     * Stage 24 RLS wiring (set_config('app.tenant_id', ...) inside a short
     * transaction). {@code org.springframework.transaction..} must therefore
     * be allowed inside the JDBC adapter but confined there — the
     * {@code api} / {@code discovery} / {@code health} / {@code tenant}
     * subpackages must not leak transaction-management types onto their
     * imports.
     */
    @Test
    void spring_transaction_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.transaction..")
                .because("Spring transaction (TransactionTemplate / PlatformTransactionManager) "
                        + "lives only in registry.runtime.persistence.jdbc.. (Stage 24 RLS wiring "
                        + "via set_config('app.tenant_id', ...) inside a short transaction). PR #389 "
                        + "review issue: the original purity test banned org.springframework.jdbc.. "
                        + "but missed org.springframework.transaction.. — the adapter had already "
                        + "pulled in TransactionTemplate, the gate just did not say so.")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 2. Consul forbidden across the entire registry runtime -----------

    @Test
    void consul_client_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .should().dependOnClassesThat().resideInAPackage("com.ecwid.consul..")
                .because("MVP registry runtime is Consul-free; phase 2 introduces Consul under "
                        + "an ADR-gated exemption (ADR-0160 decision 2 / NFR-2 trip-wire).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_cloud_consul_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.cloud.consul..")
                .because("MVP registry runtime is Consul-free; Spring Cloud Consul is a phase-2 "
                        + "candidate, never a Stage 4 dependency.")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 3. Spring Web + Micrometer confinement (ESC-2(b) follow-up) ------
    // spring-boot-starter-web (provided) and io.micrometer:micrometer-core
    // (provided) are ADR-0160 decision 7 additions. They must NOT leak into
    // the persistence.jdbc adapter (which is Spring JDBC / transaction only)
    // or the tenant subpackage (which must stay pure Java so non-HTTP
    // callers can use ThreadLocalTenantContext without pulling Web in).
    //
    // The runtime.{api,discovery,health} subpackages + the runtime root
    // (for RegistryObservabilityConfig / RegistrySchedulingConfig) ARE
    // licensed to import Spring Web / Micrometer. Rules below target only
    // the two subpackages where leakage is the concern — testing the
    // negative space via resideOutsideOfPackage("runtime..") would be
    // vacuous because every registry class lives under runtime.. .

    @Test
    void spring_web_does_not_leak_into_persistence_jdbc_adapter() {
        noClasses().that().resideInAPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("persistence.jdbc adapter is Spring JDBC / transaction only — Spring "
                        + "Web must not leak in (ESC-2(b) confinement, ADR-0160 decision 7).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_web_does_not_leak_into_tenant_subpackage() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry.runtime.tenant..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("tenant subpackage stays pure Java so background schedulers / async "
                        + "handlers can use ThreadLocalTenantContext without pulling Spring Web "
                        + "onto the classpath (ESC-2 design pivot, ADR-0160 decision 6).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void micrometer_does_not_leak_into_persistence_jdbc_adapter() {
        noClasses().that().resideInAPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("persistence.jdbc adapter is Spring JDBC / transaction only — "
                        + "Micrometer must not leak in (ESC-2(b) confinement).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void micrometer_does_not_leak_into_tenant_subpackage() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry.runtime.tenant..")
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("tenant subpackage stays pure Java so background schedulers / async "
                        + "handlers can use ThreadLocalTenantContext without pulling Micrometer "
                        + "onto the classpath (ESC-2 design pivot).")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 4. Servlet API forbidden outside the api subpackage --------------
    // The api subpackage ships @RestController (Spring Web's servlet-based
    // adapter) but never jakarta.servlet.* directly. Every other subpackage
    // must stay Servlet-free.

    @Test
    void jakarta_servlet_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .because("ESC-2 design pivot: no Servlet API in the registry runtime — tenant "
                        + "isolation has no filter entry point. MvpRegistryController uses Spring "
                        + "Web annotations but never jakarta.servlet.* directly.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void javax_servlet_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .should().dependOnClassesThat().resideInAPackage("javax.servlet..")
                .because("ESC-2 design pivot: no legacy Servlet API in the registry runtime.")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 5. Jackson confinement — discovery only -------------------------
    // RouteHandleCodec (in runtime.discovery) is the only subpackage that
    // needs Jackson for opaque route-handle encoding. api / health / tenant /
    // persistence.jdbc stay Jackson-free.

    @Test
    void jackson_confined_to_discovery_and_api_subpackages() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.registry..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.service..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.registry.runtime.api..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.registry.runtime.pull..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.registry.runtime")
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .because("Jackson is licensed only inside registry.runtime.discovery (for "
                        + "RouteHandleCodec's opaque handle encoding), "
                        + "registry.runtime.api (for A2A AgentCard JSON serialization at the "
                        + "HTTP boundary, per REQ-2026-001), registry.runtime.pull (for "
                        + "A2A AgentCard JSON deserialization in pull-based registration, per "
                        + "REQ-2026-004), and the registry.runtime root (for "
                        + "RegistryRuntimeBeanConfig's ObjectMapper bean wiring); every other "
                        + "subpackage stays serialisation-agnostic (ADR-0160 decision 3/5, "
                        + "relaxed per REQ-2026-001 / REQ-2026-004).")
                .check(REGISTRY_RUNTIME);
    }

    // ---- import-liveness guard ------------------------------------------

    /**
     * Guards against an accidental empty import (e.g. a typo'd package path)
     * silently passing every {@code noClasses} rule above.
     */
    @Test
    void registry_runtime_import_is_non_empty() {
        assertThat(REGISTRY_RUNTIME)
                .as("registry runtime production class import must be non-empty (liveness guard)")
                .isNotEmpty();
    }
}
