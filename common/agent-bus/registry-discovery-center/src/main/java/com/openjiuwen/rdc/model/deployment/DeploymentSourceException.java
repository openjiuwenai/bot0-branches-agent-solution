/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.model.deployment;

/**
 * Raised when a deployment discovery source is temporarily unreachable
 * (Feat-015 0711 {@code DEPLOYMENT_SOURCE_UNAVAILABLE}).
 *
 * <p>Providers should wrap transport / probe failures in this type instead of
 * raw {@link RuntimeException} so reconciliation can map them without catching
 * the exception base class.
 *
 * @since 0.1.0 (2026)
 */
public final class DeploymentSourceException extends RuntimeException {
    public DeploymentSourceException(String message) {
        super(message);
    }

    public DeploymentSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
