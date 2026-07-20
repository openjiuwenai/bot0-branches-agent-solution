package com.openjiuwen.rdc.controller;

/**
 * Raised when {@code POST /api/registry/register} is called while Feat-015 P1
 * deployment-discovery reconciliation is the active registration path.
 */
public class PushRegistrationDisabledException extends RuntimeException {

    public PushRegistrationDisabledException(String message) {
        super(message);
    }
}
