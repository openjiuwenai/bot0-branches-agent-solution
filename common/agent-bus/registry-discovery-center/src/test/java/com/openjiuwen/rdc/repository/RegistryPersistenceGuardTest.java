/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.rdc.model.RegistryUnavailableException;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * RegistryPersistenceGuardTest coverage.
 *
 * @since 0.1.0 (2026)
 */
class RegistryPersistenceGuardTest {
    @Test
    void data_access_exception_maps_to_registry_unavailable() {
        assertThatThrownBy(() -> RegistryPersistenceGuard.execute("trace-1", () -> {
            throw new DataAccessResourceFailureException("connection refused");
        }))
                .isInstanceOf(RegistryUnavailableException.class)
                .satisfies(ex -> {
                    if (ex instanceof RegistryUnavailableException unavailable) {
                        assertThat(unavailable.failure().failureCode()).isEqualTo("REGISTRY_UNAVAILABLE");
                    }
                });
    }

    @Test
    void cannot_create_transaction_maps_to_registry_unavailable() {
        assertThatThrownBy(() -> RegistryPersistenceGuard.execute("trace-2", () -> {
            throw new CannotCreateTransactionException(
                    "Could not open JDBC Connection for transaction",
                    new RuntimeException("permission denied for database \"agent_rdc\""));
        }))
                .isInstanceOf(RegistryUnavailableException.class)
                .satisfies(ex -> {
                    if (ex instanceof RegistryUnavailableException unavailable) {
                        assertThat(unavailable.failure().failureCode()).isEqualTo("REGISTRY_UNAVAILABLE");
                        assertThat(unavailable.getMessage()).contains("permission denied");
                    }
                });
    }
}
