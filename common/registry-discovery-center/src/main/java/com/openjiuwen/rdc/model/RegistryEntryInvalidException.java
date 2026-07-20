/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Push registry entry validation failed (0711 {@code REGISTRY_ENTRY_INVALID}).
 *
 * @since 0.1.0
 */
public final class RegistryEntryInvalidException extends RegistryFailureException {

    public RegistryEntryInvalidException(String message, String traceId) {
        super(RegistryFailure.of(
                "REGISTRY_ENTRY_INVALID",
                message != null ? message : "registry entry invalid",
                false,
                traceId != null ? traceId : ""));
    }
}
