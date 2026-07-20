/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Unified failure structure per Feat-015 0711 {@code RegistryFailure}.
 *
 * @since 0.1.0
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
