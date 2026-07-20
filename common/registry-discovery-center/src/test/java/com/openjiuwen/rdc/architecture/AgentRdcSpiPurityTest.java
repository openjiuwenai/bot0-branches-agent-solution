/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.Test;

/**
 * SPI-purity harness for {@code agent-bus} (Stage 1, Slice 1).
 *
 * <p>Asserts that the {@code com.openjiuwen.rdc.model..} packages stay pure Java:
 * no Spring, no Reactor, no Jackson, no observability SDK, no broker runtime.
 * The SPI is the transport-agnostic contract surface that every plane binds to,
 * so dragging in a framework or broker runtime here forces every consumer onto
 * that same technology. Authority: L1 development view SPI-purity rule;
 * CLAUDE.md Rule R-I sub-clause .b.
 *
 * <p>One {@code @Test} per forbidden technology so a violation reports the exact
 * offending import. Test classes are excluded so the rule only constrains the
 * shipped SPI surface.
 *
 * <p>Note: {@code java.util.concurrent.Flow} (used by {@code EnginePort}) is JDK
 * standard and is NOT {@code reactor..}; ArchUnit matches the package prefix, so
 * the JDK reactive-streams bridges is correctly allowed while Project Reactor is
 * correctly forbidden.
 *
 * <p>Assertion ID: HA-001.
 */
class AgentRdcSpiPurityTest {

    /**
     * Production SPI classes only ({@code com.openjiuwen.rdc.model} and
     * sub-packages). Test classes are excluded — the rule constrains the shipped
     * contract surface, not test scaffolding.
     */
    private static final JavaClasses SPI_PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.openjiuwen.rdc.model");

    @Test
    void spi_does_not_import_spring() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("org.springframework..")
                .because("agent-bus SPI must stay pure Java; Spring belongs in runtime bindings, "
                       + "never in the transport-agnostic contract surface.")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_project_reactor() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("reactor..")
                .because("agent-bus SPI must stay pure Java; java.util.concurrent.Flow is the "
                       + "allowed reactive-streams abstraction, not Project Reactor.")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_jackson() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson..")
                .because("agent-bus SPI must stay transport-agnostic; serialisation belongs in "
                       + "the wire binding layer, not the envelope contract.")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_micrometer() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.micrometer..")
                .because("agent-bus SPI must stay pure Java; metrics instrumentation belongs in "
                       + "runtime, not in the contract surface.")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_opentelemetry() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.opentelemetry..")
                .because("agent-bus SPI must stay pure Java; tracing SDK belongs in runtime, "
                       + "not in the contract surface.")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_kafka() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.kafka..")
                .because("agent-bus SPI must stay broker-agnostic; Kafka is a candidate runtime "
                       + "binding, never an SPI dependency (per Stage 1 forbidden scope).")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_nats() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.nats..")
                .because("agent-bus SPI must stay broker-agnostic; NATS is a candidate runtime "
                       + "binding, never an SPI dependency (per Stage 1 forbidden scope).")
                .check(SPI_PRODUCTION);
    }

    // ---- HTTP / network framework coverage (MI-001 follow-up) -------------
    // The L1 SPI-purity rule forbids ANY HTTP / Servlet / network framework:
    // the SPI is the transport-agnostic contract surface, so dragging in a
    // web stack here forces every consumer onto that same stack. One @Test per
    // forbidden prefix so a violation names the exact offending import.

    @Test
    void spi_does_not_import_jakarta_servlet() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.servlet..")
                .because("agent-bus SPI must stay transport-agnostic; the Servlet API belongs "
                       + "in an HTTP wire binding, never in the contract surface (MI-001).")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_javax_servlet() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("javax.servlet..")
                .because("agent-bus SPI must stay transport-agnostic; the legacy Servlet API "
                       + "belongs in an HTTP wire binding, never in the contract surface (MI-001).")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_jakarta_ws_rs() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("jakarta.ws.rs..")
                .because("agent-bus SPI must stay transport-agnostic; JAX-RS belongs in a REST "
                       + "wire binding, never in the contract surface (MI-001).")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_javax_ws_rs() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("javax.ws.rs..")
                .because("agent-bus SPI must stay transport-agnostic; the legacy JAX-RS API "
                       + "belongs in a REST wire binding, never in the contract surface (MI-001).")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_apache_http() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("org.apache.http..")
                .because("agent-bus SPI must stay transport-agnostic; Apache HttpClient belongs "
                       + "in an HTTP wire binding, never in the contract surface (MI-001).")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_okhttp() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("okhttp3..")
                .because("agent-bus SPI must stay transport-agnostic; OkHttp belongs in an HTTP "
                       + "wire binding, never in the contract surface (MI-001).")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_netty() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.netty..")
                .because("agent-bus SPI must stay transport-agnostic; Netty is a network runtime, "
                       + "never a contract-surface dependency (MI-001).")
                .check(SPI_PRODUCTION);
    }

    @Test
    void spi_does_not_import_vertx() {
        noClasses()
                .that().resideInAPackage("com.openjiuwen.rdc.model..")
                .should().dependOnClassesThat().resideInAPackage("io.vertx..")
                .because("agent-bus SPI must stay transport-agnostic; Vert.x is a network/reactive "
                       + "runtime, never a contract-surface dependency (MI-001).")
                .check(SPI_PRODUCTION);
    }

    // ---- import-liveness guard (MI-004 follow-up) -------------------------

    /**
     * Guards against an accidental empty import (e.g. a typo'd package path)
     * silently passing every {@code noClasses} rule above — an empty
     * {@link JavaClasses} set vacuously satisfies "no classes depend on X".
     * MI-004.
     */
    @Test
    void spi_production_import_is_non_empty() {
        assertThat(SPI_PRODUCTION)
                .as("SPI production class import must be non-empty (MI-004 liveness guard)")
                .isNotEmpty();
    }
}
