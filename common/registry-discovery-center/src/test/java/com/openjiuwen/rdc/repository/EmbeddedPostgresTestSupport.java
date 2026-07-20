/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

/**
 * Single embedded PostgreSQL per test JVM. Avoids macOS {@code kern.sysv.shmmni}
 * exhaustion when multiple integration test classes start their own instances
 * under JUnit parallel class execution.
 *
 * @since 0.1.0 (2026)
 */
public final class EmbeddedPostgresTestSupport {
    private static EmbeddedPostgres postgres;
    private static DataSource dataSource;

    private static ThreadPoolExecutor shutdownExecutor;

    private EmbeddedPostgresTestSupport() {

    }
    public static synchronized DataSource sharedDataSource() throws Exception {
        if (dataSource == null) {
            postgres = EmbeddedPostgres.builder().start();
            dataSource = postgres.getPostgresDatabase();
            Flyway.configure().dataSource(dataSource).load().migrate();
            shutdownExecutor = new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    r -> {
                        Thread hookThread = new Thread(r, "embedded-postgres-shutdown");
                        hookThread.setDaemon(true);
                        hookThread.setUncaughtExceptionHandler((t, e) -> {
                            // best-effort JVM shutdown cleanup
                        });
                        return hookThread;
                    });
            Runtime.getRuntime().addShutdownHook(shutdownExecutor.getThreadFactory().newThread(() -> {
                try {
                    if (postgres != null) {
                        postgres.close();
                    }
                } catch (IOException ex) {
                    // best-effort JVM shutdown cleanup
                } finally {
                        shutdownExecutor.shutdown();
                    }
            }));
        }
        return dataSource;
    }
}
