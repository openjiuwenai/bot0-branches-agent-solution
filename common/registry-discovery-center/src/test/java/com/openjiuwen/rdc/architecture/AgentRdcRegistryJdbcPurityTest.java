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
 * ({@code com.openjiuwen.rdc..}) after FEAT-016 MVC flatten + Feat-015 port.
 *
 * <p>Authority: ADR-0160 decisions 4 / 6 / 7 + Feat-015 package mapping into
 * {@code controller / service / repository / model / card / deployment /
 * reconcile / security / health / tenant / pull / config}.
 *
 * <p>Assertion ID: HA-002-REG.
 */
class AgentRdcRegistryJdbcPurityTest {
    private static final JavaClasses REGISTRY_RUNTIME = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.openjiuwen.rdc");

    private static final String JDBC_ADAPTER = "com.openjiuwen.rdc.repository..";
    private static final String CONFIG_ROOT = "com.openjiuwen.rdc.config..";

    @Test
    void jdbc_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("java.sql..")
                .because("JDBC lives only in repository.. (ADR-0160 decision 4).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void javax_sql_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .and().resideOutsideOfPackage(CONFIG_ROOT)
                .should().dependOnClassesThat().resideInAPackage("javax.sql..")
                .because("javax.sql (DataSource) lives only in repository.. and config wiring.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_jdbc_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..")
                .because("Spring JDBC lives only in repository.. (ADR-0160 decision 4).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_transaction_confined_to_persistence_adapter() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.transaction..")
                .because("Spring transaction lives only in repository.. (Stage 24 RLS wiring).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void consul_client_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .should().dependOnClassesThat().resideInAPackage("com.ecwid.consul..")
                .because("MVP registry runtime is Consul-free (ADR-0160 decision 2).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_cloud_consul_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.cloud.consul..")
                .because("MVP registry runtime is Consul-free.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_web_does_not_leak_into_persistence_jdbc_adapter() {
        noClasses().that().resideInAPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("repository adapter is Spring JDBC / transaction only.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void spring_web_does_not_leak_into_tenant_subpackage() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.tenant..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("tenant package stays pure Java (ADR-0160 decision 6).")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void micrometer_does_not_leak_into_persistence_jdbc_adapter() {
        noClasses().that().resideInAPackage(JDBC_ADAPTER)
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("repository adapter must not import Micrometer.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void micrometer_does_not_leak_into_tenant_subpackage() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.tenant..")
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("tenant package stays pure Java.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void jakarta_servlet_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .because("No Servlet API — controllers use Spring Web annotations only.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void javax_servlet_forbidden_in_registry_runtime() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .should().dependOnClassesThat().resideInAPackage("javax.servlet..")
                .because("No legacy Servlet API in the registry runtime.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void jackson_confined_to_licensed_packages() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.service..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.controller..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.pull..")
                .and().resideOutsideOfPackage("com.openjiuwen.rdc.card..")
                .and().resideOutsideOfPackage(CONFIG_ROOT)
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .because("Jackson is licensed only in service / controller / pull / card / config.")
                .check(REGISTRY_RUNTIME);
    }

    @Test
    void registry_runtime_import_is_non_empty() {
        assertThat(REGISTRY_RUNTIME)
                .as("registry production class import must be non-empty (liveness guard)")
                .isNotEmpty();
    }
}
