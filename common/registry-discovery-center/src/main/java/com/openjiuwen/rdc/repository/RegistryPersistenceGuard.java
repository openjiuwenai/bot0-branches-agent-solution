/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import com.openjiuwen.rdc.model.RegistryUnavailableException;

import org.springframework.dao.DataAccessException;

import java.util.function.Supplier;

/**
 * Maps persistence failures to structured {@link RegistryUnavailableException}.
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
        } catch (DataAccessException ex) {
            throw new RegistryUnavailableException(ex.getMostSpecificCause().getMessage(), traceId);
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
