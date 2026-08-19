/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.exception;

/**
 * Indicates that an intent result function could not create its action.
 *
 * @since 0.1.0
 */
public class IntentResultException extends RuntimeException {
    /**
     * Creates a result function failure.
     *
     * @param message failure description
     */
    public IntentResultException(String message) {
        super(message);
    }

    /**
     * Creates a result function failure with its cause.
     *
     * @param message failure description
     * @param cause original cause
     */
    public IntentResultException(String message, Throwable cause) {
        super(message, cause);
    }
}
