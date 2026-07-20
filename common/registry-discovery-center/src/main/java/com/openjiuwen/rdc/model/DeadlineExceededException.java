package com.openjiuwen.rdc.model;

/**
 * Operation exceeded {@link RegistryRequestContext#deadline()} (0711 {@code DEADLINE_EXCEEDED}).
 */
public final class DeadlineExceededException extends RegistryFailureException {

    public DeadlineExceededException(String traceId) {
        super(RegistryFailure.of(
                "DEADLINE_EXCEEDED",
                "request deadline exceeded",
                true,
                traceId != null ? traceId : ""));
    }
}
