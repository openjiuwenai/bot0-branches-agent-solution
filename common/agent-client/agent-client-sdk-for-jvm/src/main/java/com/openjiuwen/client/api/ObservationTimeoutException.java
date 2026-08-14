/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.client.api;

import java.time.Duration;

/**
 * Client 在约定时限内未观察到 Task 终态。
 *
 * <p>该异常只表示本地自动观察停止，不表示 Runtime Task 失败或被取消。调用方可使用
 * {@link #diagnosticTaskRef()} 记录诊断信息，并在需要时通过原 invocationRef 再次查询。
 */
public final class ObservationTimeoutException extends RuntimeException implements ClassifiedError {
    private static final long serialVersionUID = 1L;

    private final String invocationRef;
    private final String diagnosticTaskRef;
    private final TaskState lastKnownState;
    private final Duration observationTimeout;

    public ObservationTimeoutException(String invocationRef, String diagnosticTaskRef,
            TaskState lastKnownState, Duration observationTimeout) {
        super("automatic observation timed out after " + observationTimeout
                + "; invocationRef=" + invocationRef
                + "; taskId=" + diagnosticTaskRef
                + "; lastKnownState=" + lastKnownState);
        this.invocationRef = invocationRef;
        this.diagnosticTaskRef = diagnosticTaskRef;
        this.lastKnownState = lastKnownState;
        this.observationTimeout = observationTimeout;
    }

    public String invocationRef() {
        return invocationRef;
    }

    public String diagnosticTaskRef() {
        return diagnosticTaskRef;
    }

    public TaskState lastKnownState() {
        return lastKnownState;
    }

    public Duration observationTimeout() {
        return observationTimeout;
    }

    @Override
    public String code() {
        return ErrorCodes.OBSERVATION_TIMEOUT;
    }

    @Override
    public boolean retryable() {
        return false;
    }
}
