package com.openjiuwen.rdc.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Unified governance context for discovery and trusted route resolution
 * (Feat-015 0711 scope §3 {@code RegistryRequestContext}).
 */
public record RegistryRequestContext(
        String tenantId,
        String callerRef,
        String traceId,
        String requestId,
        Instant deadline
) {
    public RegistryRequestContext {
        Objects.requireNonNull(deadline, "deadline");
    }

    private static void requireNonBlank(String value, String name, String traceId) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new InvalidDiscoveryQueryException("INVALID_QUERY", name + " must not be blank", traceId);
        }
    }

    public void validate() {
        requireNonBlank(tenantId, "tenantId", traceId);
        requireNonBlank(callerRef, "callerRef", traceId);
        requireNonBlank(traceId, "traceId", traceId);
        requireNonBlank(requestId, "requestId", traceId);
    }
}
