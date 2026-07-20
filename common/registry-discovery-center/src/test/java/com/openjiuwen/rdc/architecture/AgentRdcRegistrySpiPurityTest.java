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
 * SPI-purity harness for the Stage 4 registry/discovery SPI surface
 * ({@code com.openjiuwen.rdc.model..}).
 *
 * <p>Symmetric to {@link AgentRdcSpiPurityTest} (which guards the rest of the
 * agent-rdc SPI surface). The registry SPI is the
 * transport-agnostic, persistence-agnostic, broker-agnostic contract surface
 * that every consumer (Orchestrator / Gateway / forwarding layer) depends on.
 * Dragging in a framework here forces every consumer onto that same
 * technology.
 *
 * <p>Authority: ADR-0160 decision 1 (SPI-pure); ICD-Agent-Registry-Discovery
 * HD3-001/003/006; CLAUDE.md Rule R-I sub-clause .b. The post-edit gate rules
 * {@code req-2026-003-spi-registry-no-spring/-no-jdbc/-no-jackson/-no-consul}
 * enforce the same invariants at edit time; this test is the second layer.
 *
 * <p>One {@code @Test} per forbidden technology so a violation reports the
 * exact offending import. Test classes are excluded so the rule constrains
 * the shipped SPI surface, not test scaffolding.
 *
 * <p>Assertion ID: HA-001-REG.
 */
class AgentRdcRegistrySpiPurityTest {

    /**
     * Production registry SPI classes only
     * ({@code com.openjiuwen.rdc.model} and sub-packages). Test
     * classes are excluded — the rule constrains the shipped contract surface.
     */
    private static final JavaClasses REGISTRY_SPI = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.openjiuwen.rdc.model");

    // ---- framework pollution (Spring / Reactor / observability SDK) ------

    @Test
    void spi_registry_does_not_import_spring() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("registry SPI must stay pure Java; Spring belongs in runtime bindings, "
                       + "never in the transport-agnostic contract surface (ADR-0160 decision 1).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_project_reactor() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("reactor..")
                .because("registry SPI must stay pure Java; java.util.concurrent.Flow is the "
                       + "allowed reactive-streams abstraction, not Project Reactor.")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_micrometer() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("registry SPI must stay pure Java; metrics instrumentation belongs in "
                       + "runtime (RegistryObservabilityConfig), not in the contract surface.")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_opentelemetry() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.opentelemetry..")
                .because("registry SPI must stay pure Java; tracing SDK belongs in runtime, "
                       + "not in the contract surface.")
                .check(REGISTRY_SPI);
    }

    // ---- persistence / serialisation pollution ---------------------------

    @Test
    void spi_registry_does_not_import_jdbc() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("java.sql..")
                .because("registry SPI must stay persistence-agnostic; JDBC lives in the "
                       + "runtime.persistence.jdbc adapter only (ADR-0160 decision 1/4).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_javax_sql() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("javax.sql..")
                .because("registry SPI must stay persistence-agnostic; javax.sql (DataSource) "
                       + "lives in the runtime.persistence.jdbc adapter only.")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_jackson() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .because("registry SPI must stay transport-agnostic; serialisation belongs in "
                       + "the wire binding layer (RouteHandleCodec in runtime.discovery), not "
                       + "in the envelope contract.")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_consul() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("com.ecwid.consul..")
                .because("registry SPI must stay MVP-pure; Consul is a phase-2 candidate, "
                       + "never a Stage 4 SPI dependency (ADR-0160 decision 1/2).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_spring_cloud_consul() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.cloud.consul..")
                .because("registry SPI must stay MVP-pure; Spring Cloud Consul is a phase-2 "
                       + "candidate, never a Stage 4 SPI dependency.")
                .check(REGISTRY_SPI);
    }

    // ---- HTTP / network framework pollution (MI-001 follow-up) -----------
    // The SPI is the transport-agnostic contract surface, so dragging in a
    // web stack here forces every consumer onto that same stack.

    @Test
    void spi_registry_does_not_import_jakarta_servlet() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .because("registry SPI must stay transport-agnostic; the Servlet API belongs "
                       + "in an HTTP wire binding, never in the contract surface (MI-001).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_javax_servlet() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("javax.servlet..")
                .because("registry SPI must stay transport-agnostic; the legacy Servlet API "
                       + "belongs in an HTTP wire binding, never in the contract surface.")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_jakarta_ws_rs() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.ws.rs..")
                .because("registry SPI must stay transport-agnostic; JAX-RS belongs in a REST "
                       + "wire binding, never in the contract surface (MI-001).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_javax_ws_rs() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("javax.ws.rs..")
                .because("registry SPI must stay transport-agnostic; the legacy JAX-RS API "
                       + "belongs in a REST wire binding, never in the contract surface.")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_spring_web() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework.web..")
                .because("registry SPI must stay transport-agnostic; Spring Web (RestClient / "
                       + "@RestController) belongs in runtime.{api,discovery,health}, never in "
                       + "the contract surface (ADR-0160 decision 7).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_apache_http() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.http..")
                .because("registry SPI must stay transport-agnostic; Apache HttpClient belongs "
                       + "in an HTTP wire binding, never in the contract surface (MI-001).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_okhttp() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("okhttp3..")
                .because("registry SPI must stay transport-agnostic; OkHttp belongs in an HTTP "
                       + "wire binding, never in the contract surface (MI-001).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_netty() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.netty..")
                .because("registry SPI must stay transport-agnostic; Netty is a network runtime, "
                       + "never a contract-surface dependency (MI-001).")
                .check(REGISTRY_SPI);
    }

    @Test
    void spi_registry_does_not_import_vertx() {
        noClasses().that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.vertx..")
                .because("registry SPI must stay transport-agnostic; Vert.x is a network/reactive "
                       + "runtime, never a contract-surface dependency (MI-001).")
                .check(REGISTRY_SPI);
    }

    // ---- import-liveness guard ------------------------------------------

    /**
     * Guards against an accidental empty import (e.g. a typo'd package path)
     * silently passing every {@code noClasses} rule above — an empty
     * {@link JavaClasses} set vacuously satisfies "no classes depend on X".
     */
    @Test
    void spi_registry_production_import_is_non_empty() {
        assertThat(REGISTRY_SPI)
                .as("registry SPI production class import must be non-empty (liveness guard)")
                .isNotEmpty();
    }
}
