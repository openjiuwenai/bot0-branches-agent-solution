/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import com.openjiuwen.rdc.model.RegistryFailure;
import com.openjiuwen.rdc.model.RegistryFailureException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * Shared HTTP mapping for registry API failures across
 * {@link MvpRegistryController} and {@link InstanceRouteController}.
 *
 * <p>Controller-local {@code @ExceptionHandler} methods only apply to that
 * controller — after instance/resolve endpoints moved off
 * {@code MvpRegistryController}, failures such as {@code TENANT_SCOPE_DENIED}
 * otherwise fell through to Spring Boot's default 500 error body.
 *
 * @since 0.1.0
 */
@RestControllerAdvice(basePackageClasses = {
        MvpRegistryController.class,
        InstanceRouteController.class
})
public class RegistryApiExceptionHandler {

    @ExceptionHandler(RegistryFailureException.class)
    public ResponseEntity<Map<String, Object>> handleRegistryFailure(RegistryFailureException ex) {
        RegistryFailure failure = ex.failure();
        HttpStatus status = mapFailureStatus(failure.failureCode());
        return ResponseEntity.status(status).body(registryFailureBody(failure));
    }

    @ExceptionHandler(PushRegistrationDisabledException.class)
    @ResponseStatus(HttpStatus.GONE)
    public Map<String, String> handlePushDisabled(PushRegistrationDisabledException ex) {
        return Map.of("error", "push_registration_disabled", "message", ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", "INVALID_QUERY", "message", ex.getMessage());
    }

    private static Map<String, Object> registryFailureBody(RegistryFailure failure) {
        return Map.of(
                "error", failure.failureCode(),
                "message", failure.message(),
                "retryable", failure.retryable(),
                "traceId", failure.traceId());
    }

    private static HttpStatus mapFailureStatus(String failureCode) {
        return switch (failureCode) {
            case "CALLER_NOT_AUTHORIZED", "TENANT_SCOPE_DENIED" -> HttpStatus.FORBIDDEN;
            case "ENTRY_NOT_FOUND", "MALFORMED_ROUTE_HANDLE" -> HttpStatus.NOT_FOUND;
            case "LEASE_EXPIRED" -> HttpStatus.GONE;
            case "INVALID_QUERY", "REGISTRY_ENTRY_INVALID", "REGISTRATION_INVALID" -> HttpStatus.BAD_REQUEST;
            case "REGISTRY_UNAVAILABLE", "DEADLINE_EXCEEDED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
