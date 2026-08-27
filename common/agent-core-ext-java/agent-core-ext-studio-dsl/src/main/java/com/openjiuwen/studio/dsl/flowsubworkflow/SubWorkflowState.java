/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowsubworkflow;

/**
 * Python {@code SubWorkflowState}.
 *
 * @since 2026-08-26
 */

public final class SubWorkflowState {
    private SubWorkflowExecutionStatus status = SubWorkflowExecutionStatus.START;

    /**
     * status.
     *
     * @return result
     * @since 0.1.0
     */

    public SubWorkflowExecutionStatus status() {
        return status;
    }

    /**
     * setStatus.
     *
     * @param status status
     * @since 0.1.0
     */

    public void setStatus(SubWorkflowExecutionStatus status) {
        this.status = status == null ? SubWorkflowExecutionStatus.START : status;
    }

    /**
     * shouldInterrupt.
     *
     * @return result
     * @since 0.1.0
     */

    public boolean shouldInterrupt() {
        return status == SubWorkflowExecutionStatus.USER_INTERACT;
    }

    /**
     * reset.
     *
     * @since 0.1.0
     *
     */

    public void reset() {
        status = SubWorkflowExecutionStatus.START;
    }
}
