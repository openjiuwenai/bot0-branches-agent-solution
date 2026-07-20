package com.openjiuwen.rdc.model;

/**
 * Runtime exception carrying a structured {@link RegistryFailure}.
 */
public class RegistryFailureException extends RuntimeException {

    private final RegistryFailure failure;

    public RegistryFailureException(RegistryFailure failure) {
        super(failure.message());
        this.failure = failure;
    }

    public RegistryFailure failure() {
        return failure;
    }
}
