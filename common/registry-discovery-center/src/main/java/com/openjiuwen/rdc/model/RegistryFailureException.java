/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Runtime exception carrying a structured {@link RegistryFailure}.
 *
 * @since 0.1.0 (2026)
  */
public class RegistryFailureException extends RuntimeException {

    private final RegistryFailure failure;

    public RegistryFailureException(RegistryFailure failure) {
        super(failure.message());
        this.failure = failure;
    }

    /**
     * failure.
     * @return result
     * @since 0.1.0
     */
    public RegistryFailure failure() {
        return failure;
    }
}
