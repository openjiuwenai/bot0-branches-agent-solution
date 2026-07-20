package com.openjiuwen.rdc.repository;

import com.openjiuwen.rdc.model.RegistryUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RegistryPersistenceGuardTest {

    @Test
    void data_access_exception_maps_to_registry_unavailable() {
        assertThatThrownBy(() -> RegistryPersistenceGuard.execute("trace-1", () -> {
            throw new DataAccessResourceFailureException("connection refused");
        }))
                .isInstanceOf(RegistryUnavailableException.class)
                .satisfies(ex -> assertThat(((RegistryUnavailableException) ex).failure().failureCode())
                        .isEqualTo("REGISTRY_UNAVAILABLE"));
    }
}
