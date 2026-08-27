/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.intentdetection;

/**
 * Intent detection execution state (Python {@code IntentDetectionState}).
 *
 * @since 2026-08-26
 */

public final class IntentDetectionState {

    /**
     * * * Python {@code ExecutionStatus}.
     */
    public enum ExecutionStatus {
        START,
        END,
        USER_INTERACT
    }

    private ExecutionStatus status = ExecutionStatus.START;

    /**
     * status.
     *
     * @return result
     * @since 0.1.0
     */

    public ExecutionStatus status() {
        return status;
    }

    /**
     * setStatus.
     *
     * @param status status
     * @since 0.1.0
     */

    public void setStatus(ExecutionStatus status) {
        this.status = status == null ? ExecutionStatus.START : status;
    }

    /**
     * Deep copy for {@code load_state}.
     *
     * @return result
     * @since 0.1.0
     */

    public IntentDetectionState copy() {
        IntentDetectionState copy = new IntentDetectionState();
        copy.status = this.status;
        return copy;
    }
}
