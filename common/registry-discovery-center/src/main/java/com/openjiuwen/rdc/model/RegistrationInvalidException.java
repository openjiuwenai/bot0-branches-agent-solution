/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model;

/**
 * Logical {@code AgentCardRegistration} is invalid or inconsistent (0713
 * {@code REGISTRATION_INVALID}).
 *
 * @since 0.1.0 (2026)
  */
public final class RegistrationInvalidException extends RegistryFailureException {

    public RegistrationInvalidException(String message, String traceId) {
        super(RegistryFailure.of(
                "REGISTRATION_INVALID",
                message,
                false,
                traceId));
    }
}
