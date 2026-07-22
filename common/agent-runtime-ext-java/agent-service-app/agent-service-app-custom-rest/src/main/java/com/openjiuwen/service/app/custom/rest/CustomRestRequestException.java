/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

import java.util.Objects;

/**
 * Sanitized client error reported by a custom protocol adapter.
 *
 * @since 0.1.0
 */
public final class CustomRestRequestException extends RuntimeException {
    private final int httpStatus;
    private final String code;

    public CustomRestRequestException(int httpStatus, String code, String message) {
        super(Objects.requireNonNull(message, "message"));
        if (httpStatus < 400 || httpStatus > 499) {
            throw new IllegalArgumentException("Custom REST request status must be in the 400-499 range");
        }
        this.httpStatus = httpStatus;
        this.code = Objects.requireNonNull(code, "code");
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public String getCode() {
        return code;
    }
}
