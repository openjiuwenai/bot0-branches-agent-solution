package com.openjiuwen.rdc.repository;

import com.openjiuwen.rdc.model.RegistryUnavailableException;
import org.springframework.dao.DataAccessException;

import java.util.function.Supplier;

/**
 * Maps persistence failures to structured {@link RegistryUnavailableException}.
 */
public final class RegistryPersistenceGuard {

    private RegistryPersistenceGuard() {
    }

    public static <T> T execute(String traceId, Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException ex) {
            throw new RegistryUnavailableException(ex.getMostSpecificCause().getMessage(), traceId);
        }
    }

    public static void run(String traceId, Runnable action) {
        execute(traceId, () -> {
            action.run();
            return null;
        });
    }
}
