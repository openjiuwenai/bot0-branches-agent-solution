/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Enforces {@link RegistryRequestContext#deadline()} per Feat-015 0711 §5.1.8.
 *
 * @since 0.1.0 (2026)
  */
public final class RegistryRequestDeadline {

    private RegistryRequestDeadline() {
    }

    /**
     * enforce.
     * @param deadline deadline
     * @param traceId traceId
     * @since 0.1.0
     */
    public static void enforce(Instant deadline, String traceId) {
        Objects.requireNonNull(deadline, "deadline");
        if (Instant.now().isAfter(deadline)) {
            throw new DeadlineExceededException(traceId);
        }
    }
}
