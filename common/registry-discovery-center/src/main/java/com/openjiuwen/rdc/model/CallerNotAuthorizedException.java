/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Caller failed tenant-scoped authorization (0711 {@code CALLER_NOT_AUTHORIZED}).
 *
 * @since 0.1.0 (2026)
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
