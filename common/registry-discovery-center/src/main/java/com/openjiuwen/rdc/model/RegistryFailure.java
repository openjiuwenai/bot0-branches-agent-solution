package com.openjiuwen.rdc.model;

/**
 * Unified failure structure per Feat-015 0711 {@code RegistryFailure}.
 */
public record RegistryFailure(
        String failureCode,
        String message,
        boolean retryable,
        String traceId
) {
    public static RegistryFailure of(String failureCode, String message, boolean retryable, String traceId) {
        return new RegistryFailure(failureCode, message, retryable, traceId);
    }
}
