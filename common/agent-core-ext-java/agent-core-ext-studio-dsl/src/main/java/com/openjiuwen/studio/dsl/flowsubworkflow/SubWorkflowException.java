/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowsubworkflow;

/**
 * Python {@code SubWorkflowStatusCode} / {@code build_sub_workflow_error}.
 *
 * @since 2026-08-26
 */

public final class SubWorkflowException extends RuntimeException {
    public static final int CONFIG_VALIDATION_ERROR = 101160;
    public static final int WORKFLOW_INSTANCE_NOT_FOUND = 101161;
    public static final int EXECUTION_ERROR = 101162;
    public static final int STREAM_ERROR = 101163;
    public static final int EXECUTION_TIMEOUT = 101164;

    private final int errorCode;

    public SubWorkflowException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SubWorkflowException(int errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * errorCode.
     *
     * @return result
     * @since 0.1.0
     */

    public int errorCode() {
        return errorCode;
    }

    /**
     * executionError.
     *
     * @param errorMsg errorMsg
     * @return result
     * @since 0.1.0
     */

    public static SubWorkflowException executionError(String errorMsg) {
        return new SubWorkflowException(
                EXECUTION_ERROR, "sub_workflow execution error, reason: " + errorMsg);
    }

    /**
     * executionError.
     *
     * @param errorMsg errorMsg
     * @param cause cause
     * @return result
     * @since 0.1.0
     */

    public static SubWorkflowException executionError(String errorMsg, Throwable cause) {
        return new SubWorkflowException(
                EXECUTION_ERROR, "sub_workflow execution error, reason: " + errorMsg, cause);
    }

    /**
     * streamError.
     *
     * @param errorMsg errorMsg
     * @param cause cause
     * @return result
     * @since 0.1.0
     */

    public static SubWorkflowException streamError(String errorMsg, Throwable cause) {
        return new SubWorkflowException(
                STREAM_ERROR, "sub_workflow stream error, reason: " + errorMsg, cause);
    }

    /**
     * instanceNotFound.
     *
     * @param workflowId workflowId
     * @return result
     * @since 0.1.0
     */

    public static SubWorkflowException instanceNotFound(String workflowId) {
        return new SubWorkflowException(
                WORKFLOW_INSTANCE_NOT_FOUND,
                "sub_workflow instance not found, workflow_id: " + workflowId);
    }

    /**
     * timeout.
     *
     * @param timeoutSeconds timeoutSeconds
     * @return result
     * @since 0.1.0
     */

    public static SubWorkflowException timeout(long timeoutSeconds) {
        return new SubWorkflowException(
                EXECUTION_TIMEOUT,
                "sub_workflow execution timed out after " + timeoutSeconds + "s");
    }
}
