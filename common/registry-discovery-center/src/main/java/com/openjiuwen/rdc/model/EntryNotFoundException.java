package com.openjiuwen.rdc.model;

/**
 * Registry entry or route handle target not found (0711 {@code ENTRY_NOT_FOUND}).
 */
public final class EntryNotFoundException extends RegistryFailureException {

    public EntryNotFoundException(String message, String traceId) {
        super(RegistryFailure.of(
                "ENTRY_NOT_FOUND",
                message,
                false,
                traceId != null ? traceId : ""));
    }
}
