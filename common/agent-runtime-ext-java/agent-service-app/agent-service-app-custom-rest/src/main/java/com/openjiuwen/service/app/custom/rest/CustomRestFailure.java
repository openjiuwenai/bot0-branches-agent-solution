/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.custom.rest;

final class CustomRestFailure extends RuntimeException {
    private final int httpStatus;
    private final String code;

    CustomRestFailure(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    int getHttpStatus() {
        return httpStatus;
    }

    String getCode() {
        return code;
    }

    CustomRestProtocolAdapter.CustomRestError toError() {
        return new CustomRestProtocolAdapter.CustomRestError(httpStatus, code, getMessage());
    }
}
