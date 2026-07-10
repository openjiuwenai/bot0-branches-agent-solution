/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.rdc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone entry point for the agent-bus plane (PR #389 review issue #6).
 *
 * <p>agent-bus is a runnable Spring Boot application — it ships its own
 * {@code @SpringBootApplication} so the registry / discovery runtime
 * (controller + scheduler + JDBC adapter + Flyway migration) boots in-process
 * without depending on agent-runtime's {@code LocalA2aRuntimeHost}. The
 * previously documented "library jar" positioning (KF-1) was a stage-1
 * scaffolding assumption that did not survive the stage-4 runtime promotion
 * (ADR-0160): the registry controller, probe scheduler, and observability
 * facade are first-class runtime components, not library scaffolding that
 * the runtime consumer assembles.
 *
 * <p>Component scan covers {@code com.openjiuwen.rdc..} so
 * {@link com.openjiuwen.rdc.registry.runtime.RegistrySchedulingConfig}
 * (which anchors {@code @EnableScheduling} for the probe scheduler) and
 * {@link com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig}
 * are picked up automatically. The {@code @RestController} /
 * {@code @Scheduled} / {@code @Service} / {@code @Configuration} annotations
 * are visible at compile time because {@code spring-boot-starter-web} is at
 * compile scope in {@code agent-bus/pom.xml} (no longer {@code provided}).
 *
 * <p>Run with {@code ./mvnw -pl agent-bus spring-boot:run} or build the
 * runnable jar with {@code ./mvnw -pl agent-bus package} and execute
 * {@code java -jar agent-bus/target/agent-bus-*.jar}.
 *
 * <p>Authority: PR #389 review issue #6 + ADR-0160 decision 7 (scope
 * evolution: provided → compile, agent-bus now ships its own runtime).
 *
 * @since 2026-07-10
 */
@SpringBootApplication
public class AgentRdcApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentRdcApplication.class, args);
    }
}
