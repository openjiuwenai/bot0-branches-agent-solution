/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Unified failure structure per Feat-015 0711 {@code RegistryFailure}.
 *
 * @since 0.1.0 (2026)
 * @param failureCode failureCode
 * @param message message
 * @param retryable retryable
 * @param traceId traceId
 * @return result
 */
public record RegistryFailure(
        String failureCode,
        String message,
        boolean retryable,
        String traceId
) {

    /**
     * of.
     *
     * @param failureCode failureCode
     * @param message message
     * @param retryable retryable
     * @param traceId traceId
     * @return result
     * @since 0.1.0
     */
    public static RegistryFailure of(String failureCode, String message, boolean retryable, String traceId) {
        return new RegistryFailure(failureCode, message, retryable, traceId);
    }
}
