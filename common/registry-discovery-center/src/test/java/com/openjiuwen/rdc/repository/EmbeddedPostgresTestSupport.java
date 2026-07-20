package com.openjiuwen.rdc.repository;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;

import javax.sql.DataSource;

/**
 * Single embedded PostgreSQL per test JVM. Avoids macOS {@code kern.sysv.shmmni}
 * exhaustion when multiple integration test classes start their own instances
 * under JUnit parallel class execution.
 */
public final class EmbeddedPostgresTestSupport {

    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private EmbeddedPostgresTestSupport() {
    }

    public static synchronized DataSource sharedDataSource() throws Exception {
        if (dataSource == null) {
            postgres = EmbeddedPostgres.builder().start();
            dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).load().migrate();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    if (postgres != null) {
                        postgres.close();
                    }
                } catch (Exception ignored) {
                    // best-effort JVM shutdown cleanup
                }
            }, "embedded-postgres-shutdown"));
        }
        return dataSource;
    }
}
