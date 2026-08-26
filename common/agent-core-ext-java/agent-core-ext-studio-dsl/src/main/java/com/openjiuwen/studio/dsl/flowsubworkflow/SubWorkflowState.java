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

    public SubWorkflowExecutionStatus status() {
        return status;
    }

    public void setStatus(SubWorkflowExecutionStatus status) {
        this.status = status == null ? SubWorkflowExecutionStatus.START : status;
    }

    public boolean shouldInterrupt() {
        return status == SubWorkflowExecutionStatus.USER_INTERACT;
    }

    public void reset() {
        status = SubWorkflowExecutionStatus.START;
    }
}
