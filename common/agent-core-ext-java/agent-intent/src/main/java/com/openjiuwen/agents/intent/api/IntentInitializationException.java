/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

/**
 * Indicates that a complete intent catalog could not be initialized.
 *
 * @since 0.1.0
 */
public class IntentInitializationException extends RuntimeException {
    /**
     * Creates an initialization failure.
     *
     * @param message failure description
     */
    public IntentInitializationException(String message) {
        super(message);
    }

    /**
     * Creates an initialization failure with its cause.
     *
     * @param message failure description
     * @param cause original failure
     */
    public IntentInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
