/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime.pull;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.registry.runtime.RegistryObservabilityConfig;
import com.openjiuwen.rdc.repository.JdbcAgentRegistryRepository;
import com.openjiuwen.rdc.model.FrameworkType;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * End-to-end integration test for {@link PullRegistrationBootstrap} against a
 * real PostgreSQL (Zonky embedded-postgres) + the full Flyway migration chain.
 *
 * <p>The existing {@link PullRegistrationBootstrapTest} uses a recording fake
 * repository and therefore cannot catch DB-level constraint violations. This
 * test boots the real {@link JdbcAgentRegistryRepository} against an in-process
 * PG with V2→V3→V4 migrations applied, serves an A2A AgentCard via
 * {@link MockWebServer}, and asserts the pulled entry is actually persisted.
 *
 * <p>Regression coverage for the REQ-2026-004 pull-registration bug where
 * {@code buildEntry()} omitted {@code maxConcurrency}/{@code weight}, the
 * upsert SQL bound those columns as null, and the NOT NULL constraint on
 * {@code agent_registry_mvp} rejected the insert.
 *
 * @since 2026-07-10
 */
class PullRegistrationBootstrapPgIntegrationTest {
    private static EmbeddedPostgres pg;
    private static DataSource dataSource;
    private static MockWebServer runtimeServer;

    private static JdbcAgentRegistryRepository repository;
    private static RegistryObservabilityConfig observability;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void bootStack() throws Exception {
        pg = EmbeddedPostgres.builder().start();
        dataSource = pg.getPostgresDatabase();
        Flyway.configure().dataSource(dataSource).load().migrate();

        runtimeServer = new MockWebServer();
        runtimeServer.start();

        repository = new JdbcAgentRegistryRepository(dataSource);
        observability = new RegistryObservabilityConfig(new SimpleMeterRegistry());
        objectMapper = new ObjectMapper();
    }

    @AfterAll
    static void shutDown() throws Exception {
        if (runtimeServer != null) {
            runtimeServer.shutdown();
        }
        if (pg != null) {
            pg.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        new JdbcTemplate(dataSource).execute("DELETE FROM agent_registry_mvp");
    }

    @Test
    void pull_upserts_defaults_when_maxconcurrency_weight_omitted() throws Exception {
        String cardJson = "{\"name\":\"财务助手\",\"description\":\"billing\","
                + "\"version\":\"1.0.0\",\"capabilities\":{\"streaming\":false,"
                + "\"pushNotifications\":false,\"stateTransitionHistory\":false},"
                + "\"defaultInputModes\":[],\"defaultOutputModes\":[],"
                + "\"skills\":[],\"supportedInterfaces\":[]}";
        runtimeServer.enqueue(new MockResponse()
                .setBody(cardJson)
                .setHeader("Content-Type", "application/json"));

        PullRegistrationProperties props = new PullRegistrationProperties();
        props.setEnabled(true);
        PullRegistrationProperties.RuntimeEntry runtime = new PullRegistrationProperties.RuntimeEntry();
        runtime.setBaseUrl(runtimeServer.url("/").toString().replaceAll("/$", ""));
        runtime.setTenantId("tenant-pull");
        runtime.setAgentId("agent-pull");
        runtime.setFrameworkType(FrameworkType.JIUWEN);
        props.getRuntimes().add(runtime);

        PullRegistrationBootstrap bootstrap = new PullRegistrationBootstrap(
                props, repository, observability, objectMapper);

        bootstrap.runBootstrap();

        Integer maxConcurrency = readInt("max_concurrency", "tenant-pull", "agent-pull");
        Integer weight = readInt("weight", "tenant-pull", "agent-pull");
        assertThat(maxConcurrency)
                .as("pull path must persist max_concurrency (NOT NULL column)")
                .isNotNull()
                .isEqualTo(10);
        assertThat(weight)
                .as("pull path must persist weight (NOT NULL column)")
                .isNotNull()
                .isEqualTo(100);
    }

    @Test
    void pull_honors_operator_pinned_max_concurrency_and_weight() throws Exception {
        String cardJson = "{\"name\":\"财务助手\",\"description\":\"billing\","
                + "\"version\":\"1.0.0\",\"capabilities\":{\"streaming\":false,"
                + "\"pushNotifications\":false,\"stateTransitionHistory\":false},"
                + "\"defaultInputModes\":[],\"defaultOutputModes\":[],"
                + "\"skills\":[],\"supportedInterfaces\":[]}";
        runtimeServer.enqueue(new MockResponse()
                .setBody(cardJson)
                .setHeader("Content-Type", "application/json"));

        PullRegistrationProperties props = new PullRegistrationProperties();
        props.setEnabled(true);
        PullRegistrationProperties.RuntimeEntry runtime = new PullRegistrationProperties.RuntimeEntry();
        runtime.setBaseUrl(runtimeServer.url("/").toString().replaceAll("/$", ""));
        runtime.setTenantId("tenant-pull");
        runtime.setAgentId("agent-pull-2");
        runtime.setFrameworkType(FrameworkType.JIUWEN);
        runtime.setMaxConcurrency(42);
        runtime.setWeight(7);
        props.getRuntimes().add(runtime);

        PullRegistrationBootstrap bootstrap = new PullRegistrationBootstrap(
                props, repository, observability, objectMapper);

        bootstrap.runBootstrap();

        assertThat(readInt("max_concurrency", "tenant-pull", "agent-pull-2")).isEqualTo(42);
        assertThat(readInt("weight", "tenant-pull", "agent-pull-2")).isEqualTo(7);
    }

    private Integer readInt(String column, String tenant, String agent) {
        return new JdbcTemplate(dataSource).queryForObject(
                "SELECT " + column + " FROM agent_registry_mvp WHERE tenant_id = ? AND agent_id = ?",
                Integer.class, tenant, agent);
    }
}
