package com.openjiuwen.rdc.model;

/**
 * Logical {@code AgentCardRegistration} is invalid or inconsistent (0713
 * {@code REGISTRATION_INVALID}).
 */
public final class RegistrationInvalidException extends RegistryFailureException {

    public RegistrationInvalidException(String message, String traceId) {
        super(RegistryFailure.of(
                "REGISTRATION_INVALID",
                message,
                false,
                traceId));
    }
}
