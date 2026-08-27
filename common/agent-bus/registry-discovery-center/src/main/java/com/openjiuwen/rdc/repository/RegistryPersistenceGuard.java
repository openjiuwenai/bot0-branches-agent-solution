/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import com.openjiuwen.rdc.model.RegistryUnavailableException;

import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;

import java.util.function.Supplier;

/**
 * Maps persistence failures to structured {@link RegistryUnavailableException}.
 *
 * <p>Covers both {@link DataAccessException} (SQL after a connection is leased)
 * and {@link TransactionException} (e.g. {@code CannotCreateTransactionException}
 * when Hikari cannot open a JDBC connection — common when PostgreSQL is down or
 * CONNECT is revoked). Without the latter, FEAT-016 L1 cache fallback never runs.
 *
 * @since 0.1.0 (2026)
 */
public final class RegistryPersistenceGuard {
    private RegistryPersistenceGuard() {

    }

    /**
     * execute.
     *
     * @param traceId traceId
     * @param action action
     * @return result
     * @since 0.1.0
     */
    public static <T> T execute(String traceId, Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException | TransactionException ex) {
            Throwable cause = ex.getMostSpecificCause();
            String detail = cause != null && cause.getMessage() != null
                    ? cause.getMessage()
                    : ex.getMessage();
            throw new RegistryUnavailableException(detail, traceId);
        }
    }

    /**
     * run.
     *
     * @param traceId traceId
     * @param action action
     * @since 0.1.0
     */
    public static void run(String traceId, Runnable action) {
        execute(traceId, () -> {
            action.run();
            return null;
        });
    }
}
