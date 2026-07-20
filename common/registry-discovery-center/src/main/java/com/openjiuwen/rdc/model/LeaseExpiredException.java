/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Route handle points to an entry whose lease has expired (0711 {@code LEASE_EXPIRED}).
 *
 * @since 0.1.0
 */
public final class LeaseExpiredException extends RegistryFailureException {

    public LeaseExpiredException(String traceId) {
        super(RegistryFailure.of(
                "LEASE_EXPIRED",
                "registry entry lease has expired",
                false,
                traceId));
    }
}
