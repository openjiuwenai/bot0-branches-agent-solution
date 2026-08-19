/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.rdc.model.MalformedRouteHandleException;
import com.openjiuwen.rdc.model.TenantIsolationViolationException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Feat-015 discover/register mapping on {@link RegistryApiExceptionHandler}
 * is unchanged after FEAT-016 gained its own HTTP handler.
 */
class RegistryApiExceptionHandlerTest {
    private final RegistryApiExceptionHandler handler = new RegistryApiExceptionHandler();

    @Test
    void tenantScopeDeniedStays403OnFeat015Handler() {
        ResponseEntity<Map<String, Object>> response = handler.handleRegistryFailure(
                new TenantIsolationViolationException("tenant-A", "tenant-B", "trace-015"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("error", "TENANT_SCOPE_DENIED");
    }

    @Test
    void malformedHandleStays404OnFeat015Handler() {
        ResponseEntity<Map<String, Object>> response = handler.handleRegistryFailure(
                new MalformedRouteHandleException("bad handle", "trace-015"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("error", "MALFORMED_ROUTE_HANDLE");
    }
}
