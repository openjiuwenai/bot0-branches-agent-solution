/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.wait;

import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;

import java.util.Optional;

/**
 * Dual-window (accept/response) per-invocation wait (FEAT-012 §1.5.1 / §4.6).
 *
 * @since 2026-07-24
 */
public class WaitWindow {
    private final long acceptDeadlineMillis;
    private final long responseWindowMillis;
    private Long acceptedAtMillis;
    private String taskId;
    private InvocationResponseStatus folded;
    private boolean released;

    /**
     * Opens a wait window from the given start instant and configured durations.
     *
     * @param startMillis invocation start (epoch millis)
     * @param acceptWindowMillis accept-phase timeout
     * @param responseWindowMillis response-phase timeout after accept
     */
    public WaitWindow(long startMillis, long acceptWindowMillis, long responseWindowMillis) {
        this.acceptDeadlineMillis = startMillis + acceptWindowMillis;
        this.responseWindowMillis = responseWindowMillis;
    }

    /**
     * Feeds a folded projection status into the window.
     *
     * @param status folded projection status
     * @param taskId task id when status is ACCEPTED_WITH_TASK
     * @param nowMillis current instant (epoch millis)
     */
    public void onProjection(InvocationResponseStatus status, String taskId, long nowMillis) {
        if (folded != null || released) {
            return;
        }
        if (status == InvocationResponseStatus.ACCEPTED_WITH_TASK && this.taskId == null) {
            this.taskId = taskId;
            this.acceptedAtMillis = nowMillis;
        }
        if (FiveStateFolder.isTerminal(status)) {
            this.folded = status;
        }
    }

    /**
     * Returns the folded status if terminal or timed-out; empty if still waiting.
     *
     * @param nowMillis current instant (epoch millis)
     * @return terminal or timeout status, or empty while waiting / after release
     */
    public Optional<InvocationResponseStatus> checkTimeout(long nowMillis) {
        if (released) {
            return Optional.empty();
        }
        if (folded != null) {
            return Optional.of(folded);
        }
        if (taskId == null) {
            if (nowMillis >= acceptDeadlineMillis) {
                return Optional.of(InvocationResponseStatus.UNKNOWN);
            }
        } else {
            long respDeadline = acceptedAtMillis + responseWindowMillis;
            if (nowMillis >= respDeadline) {
                return Optional.of(InvocationResponseStatus.ACCEPTED_WITH_TASK);
            }
        }
        return Optional.empty();
    }

    /**
     * Returns the task id captured on accept.
     *
     * @return the task id captured on accept, or {@code null}
     */
    public String taskId() {
        return taskId;
    }

    /**
     * Returns the folded terminal status when known.
     *
     * @return the folded terminal status, or {@code null}
     */
    public InvocationResponseStatus folded() {
        return folded;
    }

    /**
     * Releases the window (e.g. client disconnect); subsequent polls return empty.
     */
    public void release() {
        this.released = true;
    }

    /**
     * Whether this wait window has been released.
     *
     * @return whether the window has been released
     */
    public boolean isReleased() {
        return released;
    }
}
