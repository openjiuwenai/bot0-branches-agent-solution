/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Raised when a {@link DiscoveryQuery} is malformed (Feat-015 0711
 * {@code INVALID_QUERY}).
 *
 * @since 0.1.0 (2026)
 */
public final class InvalidDiscoveryQueryException extends RegistryFailureException {
    public InvalidDiscoveryQueryException(String failureCode, String message) {
        this(failureCode, message, null);
    }
    public InvalidDiscoveryQueryException(String failureCode, String message, String traceId) {
        super(RegistryFailure.of(
                failureCode,
                message,
                false,
                traceId != null ? traceId : ""));
    }

    /**
     * failureCode.
     *
     * @return result
     * @since 0.1.0
     */
    public String failureCode() {
        return failure().failureCode();
    }
}
