package com.openjiuwen.rdc.model;

/**
 * Caller failed tenant-scoped authorization (0711 {@code CALLER_NOT_AUTHORIZED}).
 */
public final class CallerNotAuthorizedException extends RegistryFailureException {

    public CallerNotAuthorizedException(String tenantId, String callerRef, String traceId) {
        super(RegistryFailure.of(
                "CALLER_NOT_AUTHORIZED",
                "caller '" + callerRef + "' is not authorized for tenant '" + tenantId + "'",
                false,
                traceId));
    }
}
