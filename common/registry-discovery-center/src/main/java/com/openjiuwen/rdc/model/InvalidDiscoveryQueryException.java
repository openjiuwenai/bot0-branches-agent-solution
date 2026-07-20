package com.openjiuwen.rdc.model;

/**
 * Raised when a {@link DiscoveryQuery} is malformed (Feat-015 0711
 * {@code INVALID_QUERY}).
 */
public final class InvalidDiscoveryQueryException extends RegistryFailureException {

    public InvalidDiscoveryQueryException(String failureCode, String message) {
        this(failureCode, message, null);
    }

    public InvalidDiscoveryQueryException(String failureCode, String message, String traceId) {
        super(RegistryFailure.of(
                failureCode,
                message,
                false,
                traceId != null ? traceId : ""));
    }

    public String failureCode() {
        return failure().failureCode();
    }
}
