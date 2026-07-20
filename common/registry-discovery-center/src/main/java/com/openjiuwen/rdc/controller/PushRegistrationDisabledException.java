/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.controller;

/**
 * Raised when {@code POST /api/registry/register} is called while Feat-015 P1
 * deployment-discovery reconciliation is the active registration path.
 *
 * @since 0.1.0 (2026)
 */
public class PushRegistrationDisabledException extends RuntimeException {
    public PushRegistrationDisabledException(String message) {
        super(message);
    }
}
