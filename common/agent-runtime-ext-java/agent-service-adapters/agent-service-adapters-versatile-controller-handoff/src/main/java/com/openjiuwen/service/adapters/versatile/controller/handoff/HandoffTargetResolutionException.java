/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

/**
 * Raised when a handoff target cannot be uniquely resolved or fails the allowlist.
 * Carries the spec-level error code for the TYPE_ERROR payload.
 *
 * @since 2026-08-19
 */
public class HandoffTargetResolutionException extends RuntimeException {
    private final String errorCode;

    public HandoffTargetResolutionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
