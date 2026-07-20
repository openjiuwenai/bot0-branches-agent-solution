/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Operation exceeded {@link RegistryRequestContext#deadline()} (0711 {@code DEADLINE_EXCEEDED}).
 *
 * @since 0.1.0 (2026)
 */
public final class DeadlineExceededException extends RegistryFailureException {
    public DeadlineExceededException(String traceId) {
        super(RegistryFailure.of(
                "DEADLINE_EXCEEDED",
                "request deadline exceeded",
                true,
                traceId != null ? traceId : ""));
    }
}
