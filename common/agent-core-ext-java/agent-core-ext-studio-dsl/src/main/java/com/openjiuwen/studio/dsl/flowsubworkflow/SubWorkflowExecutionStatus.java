/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowsubworkflow;

/**
 * Python {@code ExecutionStatus}.
 *
 * @since 2026-08-26
 */
public enum SubWorkflowExecutionStatus {
    START("start"),
    END("end"),
    USER_INTERACT("user_interact");

    private final String value;

    SubWorkflowExecutionStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static boolean isUserInteract(Object status) {
        if (status == null) {
            return false;
        }
        if (status instanceof SubWorkflowExecutionStatus s) {
            return s == USER_INTERACT;
        }
        String v = String.valueOf(status);
        return USER_INTERACT.value.equalsIgnoreCase(v) || "USER_INTERACT".equalsIgnoreCase(v);
    }
}
