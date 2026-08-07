/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.api;

/**
 * Indicates an intent matcher execution or result mapping failure.
 *
 * @since 0.1.0
 */
public class IntentMatchException extends RuntimeException {
    /**
     * Creates a match failure.
     *
     * @param message failure description
     */
    public IntentMatchException(String message) {
        super(message);
    }

    /**
     * Creates a match failure with its cause.
     *
     * @param message failure description
     * @param cause original failure
     */
    public IntentMatchException(String message, Throwable cause) {
        super(message, cause);
    }
}
