package com.openjiuwen.rdc.model;

/**
 * Registry persistence or infrastructure unavailable (0711 {@code REGISTRY_UNAVAILABLE}).
 */
public final class RegistryUnavailableException extends RegistryFailureException {

    public RegistryUnavailableException(String message, String traceId) {
        super(RegistryFailure.of(
                "REGISTRY_UNAVAILABLE",
                message != null ? message : "registry unavailable",
                true,
                traceId != null ? traceId : ""));
    }
}
