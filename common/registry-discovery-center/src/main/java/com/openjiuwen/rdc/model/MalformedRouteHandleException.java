package com.openjiuwen.rdc.model;

/**
 * Opaque route handle could not be decoded (0711 {@code MALFORMED_ROUTE_HANDLE}).
 */
public final class MalformedRouteHandleException extends RegistryFailureException {

    public MalformedRouteHandleException(String message, String traceId) {
        super(RegistryFailure.of(
                "MALFORMED_ROUTE_HANDLE",
                message,
                false,
                traceId != null ? traceId : ""));
    }
}
