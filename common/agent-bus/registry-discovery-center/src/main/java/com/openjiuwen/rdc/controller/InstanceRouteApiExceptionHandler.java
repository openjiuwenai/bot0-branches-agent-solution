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
 * HTTP mapping for FEAT-016 instance list and route-handle resolve.
 *
 * <p>Internal {@link RegistryFailure#failureCode()} is unchanged on the
 * exception types. This handler only translates the HTTP status and public
 * {@code error} field for instance/resolve (Feat-Func-016 §7).
 *
 * @since 0.1.0 (2026)
 */
@RestControllerAdvice(basePackageClasses = InstanceRouteController.class)
public class InstanceRouteApiExceptionHandler {
    /**
     * Maps {@link RegistryFailureException} to the FEAT-016 HTTP contract.
     *
     * @param ex registry failure raised by instance/resolve
     * @return status and body with the public error code
     */
    @ExceptionHandler(RegistryFailureException.class)
    public ResponseEntity<Map<String, Object>> handleRegistryFailure(RegistryFailureException ex) {
        RegistryFailure failure = ex.failure();
        String publicCode = publicErrorCode(failure.failureCode());
        HttpStatus status = mapFailureStatus(failure.failureCode());
        return ResponseEntity.status(status).body(Map.of(
                "error", publicCode,
                "message", failure.message(),
                "retryable", failure.retryable(),
                "traceId", failure.traceId()));
    }

    /**
     * Maps illegal arguments to HTTP 400 Bad Request.
     *
     * @param ex missing path/body fields
     * @return INVALID_QUERY body
     */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
        return Map.of("error", "INVALID_QUERY", "message", ex.getMessage());
    }

    private static String publicErrorCode(String failureCode) {
        return switch (failureCode) {
            case "MALFORMED_ROUTE_HANDLE" -> "malformed_handle";
            case "TENANT_SCOPE_DENIED" -> "tenant_isolation_violation";
            case "ENTRY_NOT_FOUND" -> "entry_not_found";
            default -> failureCode;
        };
    }

    private static HttpStatus mapFailureStatus(String failureCode) {
        return switch (failureCode) {
            case "CALLER_NOT_AUTHORIZED" -> HttpStatus.FORBIDDEN;
            case "TENANT_SCOPE_DENIED", "MALFORMED_ROUTE_HANDLE" -> HttpStatus.BAD_REQUEST;
            case "ENTRY_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "LEASE_EXPIRED" -> HttpStatus.GONE;
            case "INVALID_QUERY", "REGISTRY_ENTRY_INVALID", "REGISTRATION_INVALID" -> HttpStatus.BAD_REQUEST;
            case "REGISTRY_UNAVAILABLE", "DEADLINE_EXCEEDED" -> HttpStatus.SERVICE_UNAVAILABLE;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
