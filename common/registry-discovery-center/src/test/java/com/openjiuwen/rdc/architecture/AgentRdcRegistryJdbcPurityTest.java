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
 * Layered purity harness for the registry-discovery-center module
 * ({@code com.openjiuwen.rdc..}).
 *
 * <p>Three invariants are pinned here (FEAT-016 §3.1 refactor: package
 * targets updated from the old {@code registry.runtime.*} layout to the
 * flattened {@code controller / service / repository / model / health /
 * tenant / pull / config} layout):
 *
 * <ol>
 *   <li><b>JDBC confinement</b> — {@code java.sql} / {@code javax.sql} /
 *       {@code org.springframework.jdbc..} / {@code org.springframework.transaction..}
 *       may be imported ONLY inside {@code com.openjiuwen.rdc.repository..}.
 *       Every other package calls the
 *       {@link com.openjiuwen.rdc.repository.AgentRegistryRepository} port
 *       and never touches JDBC directly (ADR-0160 decision 4).</li>
 *   <li><b>Consul forbidden</b> — the entire {@code com.openjiuwen.rdc..}
 *       module is Consul-free in MVP. Phase 2 introduces Consul under an
 *       ADR-gated exemption (ADR-0160 decision 2 / NFR-2 trip-wire).</li>
 *   <li><b>Spring Web + Micrometer confinement</b> (ESC-2(b) follow-up) —
 *       {@code org.springframework.web..} and {@code io.micrometer..} may be
 *       imported ONLY inside {@code controller..}, {@code service..},
 *       {@code health..}, and {@code config..}. The {@code tenant} package
 *       stays pure Java so non-HTTP-entry callers (schedulers, async
 *       handlers) can use {@code ThreadLocalTenantContext} without pulling
 *       Servlet / Web onto the classpath. The {@code repository} package
 *       also stays free of Spring Web / Micrometer — it imports Spring JDBC
 *       / transaction types but not Web / Micrometer.</li>
 * </ol>
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + ESC-2(b) boundary evolution
 * + FEAT-016 §3.1 package refactor. The post-edit gate rule
 * {@code req-2026-003-jdbc-confined-to-persistence} enforces the JDBC
 * invariant at edit time; this test is the second layer.
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
     * Production registry-discovery-center classes only
     * ({@code com.openjiuwen.rdc} and sub-packages). Test classes are
     * excluded.
     */
    private static final JavaClasses REGISTRY_RUNTIME = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.openjiuwen.rdc");

    /**
     * The package that IS allowed to import JDBC / Spring JDBC / Spring
     * transaction types. Everything outside this set is JDBC-free.
     */
    private static final String JDBC_ADAPTER = "com.openjiuwen.rdc.repository..";

    /**
     * The {@code config} package (root wiring: RegistryRuntimeBeanConfig
     * constructs JdbcAgentRegistryRepository + Flyway from a DataSource) is
     * also licensed for {@code javax.sql.DataSource}.
     */
    private static final String CONFIG_ROOT = "com.openjiuwen.rdc.config..";

    // ---- 1. JDBC confinement (ADR-0160 decision 4) -----------------------

    @Test
    void jdbc_confined_to_repository() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("java.sql..")
                .because("JDBC lives only in repository.. (ADR-0160 decision 4); "
                        + "controller / service / health / tenant call the "
                        + "AgentRegistryRepository port and never touch JDBC directly.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void javax_sql_confined_to_repository_and_config() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .and().resideOutsideOfPackage(CONFIG_ROOT)
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.registry.runtime")
                .should().dependOnClassesThat().resideInAPackage("javax.sql..")
                .because("javax.sql (DataSource) lives only in repository.. "
                        + "(ADR-0160 decision 4) and the config wiring package "
                        + "(RegistryRuntimeBeanConfig constructs JdbcAgentRegistryRepository + "
                        + "Flyway from a DataSource); every other package stays pure Java.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_jdbc_confined_to_repository() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
                .because("Spring JDBC (NamedParameterJdbcTemplate / RowMapper) lives only in "
                        + "repository.. (ADR-0160 decision 4).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_transaction_confined_to_repository() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.transaction..")
                .because("Spring transaction (TransactionTemplate / PlatformTransactionManager) "
                        + "lives only in repository.. (Stage 24 RLS wiring "
                        + "via set_config('app.tenant_id', ...) inside a short transaction).")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 2. Consul forbidden across the entire module --------------------

    @Test
    void consul_client_forbidden_in_module() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .should().dependOnClassesThat().resideInAPackage("com.ecwid.consul..")
                .because("MVP registry runtime is Consul-free; phase 2 introduces Consul under "
                        + "an ADR-gated exemption (ADR-0160 decision 2 / NFR-2 trip-wire).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_cloud_consul_forbidden_in_module() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.cloud.consul..")
                .because("MVP registry runtime is Consul-free; Spring Cloud Consul is a phase-2 "
                        + "candidate, never a Stage 4 dependency.")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 3. Spring Web + Micrometer confinement (ESC-2(b) follow-up) ------

    @Test
    void spring_web_does_not_leak_into_repository() {
        noClasses().that().resideInAPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("repository is Spring JDBC / transaction only — Spring Web must not "
                        + "leak in (ESC-2(b) confinement, ADR-0160 decision 7).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_web_does_not_leak_into_tenant() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.tenant..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("tenant package stays pure Java so background schedulers / async "
                        + "handlers can use ThreadLocalTenantContext without pulling Spring Web "
                        + "onto the classpath (ESC-2 design pivot, ADR-0160 decision 6).")
                .allowEmptyShould(true)
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void micrometer_does_not_leak_into_repository() {
        noClasses().that().resideInAPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("repository is Spring JDBC / transaction only — Micrometer must not "
                        + "leak in (ESC-2(b) confinement).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void micrometer_does_not_leak_into_tenant() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.tenant..")
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("tenant package stays pure Java so background schedulers / async "
                        + "handlers can use ThreadLocalTenantContext without pulling Micrometer "
                        + "onto the classpath (ESC-2 design pivot).")
                .allowEmptyShould(true)
                .check(REGISTRY_RUNTIME);
    }

    // ---- 4. Servlet API forbidden outside controller ---------------------

    @Test
    void jakarta_servlet_forbidden_in_module() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .because("ESC-2 design pivot: no Servlet API in the module — tenant isolation "
                        + "has no filter entry point. MvpRegistryController uses Spring Web "
                        + "annotations but never jakarta.servlet.* directly.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void javax_servlet_forbidden_in_module() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .should().dependOnClassesThat().resideInAPackage("javax.servlet..")
                .because("ESC-2 design pivot: no legacy Servlet API in the module.")
                .check(REGISTRY_RUNTIME);
    }

    // ---- 5. Jackson confinement — service / controller / pull / config --

    @Test
    void jackson_confined_to_service_controller_pull_config() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.service..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.controller..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.pull..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.config..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.controller..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.registry.runtime.pull..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.registry.runtime")
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .because("Jackson is licensed only inside service (RouteHandleCodec opaque "
                        + "handle encoding), controller (A2A AgentCard JSON serialization at "
                        + "the HTTP boundary, REQ-2026-001), pull (A2A AgentCard JSON "
                        + "deserialization in pull-based registration, REQ-2026-004), and config "
                        + "(RegistryRuntimeBeanConfig ObjectMapper bean wiring); every other "
                        + "package stays serialisation-agnostic (ADR-0160 decision 3/5).")
                .check(REGISTRY_RUNTIME);
    }

    // ---- import-liveness guard ------------------------------------------

    @Test
    void module_import_is_non_empty() {
        assertThat(REGISTRY_RUNTIME)
                .as("registry-discovery-center production class import must be non-empty (liveness guard)")
                .isNotEmpty();
    }
}
