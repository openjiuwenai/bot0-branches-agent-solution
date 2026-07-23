package com.openjiuwen.gateway.bus.wait;

import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;

/** Dual-window (accept/response) per-invocation wait (FEAT-012 §1.5.1 / §4.6). */
public class WaitWindow {
    private final long acceptDeadlineMillis;
    private final long responseWindowMillis;
    private Long acceptedAtMillis;
    private String taskId;
    private InvocationResponseStatus folded;
    private boolean released;

    public WaitWindow(long startMillis, long acceptWindowMillis, long responseWindowMillis) {
        this.acceptDeadlineMillis = startMillis + acceptWindowMillis;
        this.responseWindowMillis = responseWindowMillis;
    }

    /** Feed a folded projection status into the window. */
    public void onProjection(InvocationResponseStatus status, String taskId, long nowMillis) {
        if (folded != null || released) return;
        if (status == InvocationResponseStatus.ACCEPTED_WITH_TASK && this.taskId == null) {
            this.taskId = taskId;
            this.acceptedAtMillis = nowMillis;
        }
        if (FiveStateFolder.isTerminal(status)) {
            this.folded = status;
        }
    }

    /** @return the folded status if terminal or timed-out; null if still waiting. */
    public InvocationResponseStatus checkTimeout(long nowMillis) {
        if (released) return null;
        if (folded != null) return folded;
        if (taskId == null) {
            if (nowMillis >= acceptDeadlineMillis) return InvocationResponseStatus.UNKNOWN;
        } else {
            long respDeadline = acceptedAtMillis + responseWindowMillis;
            if (nowMillis >= respDeadline) return InvocationResponseStatus.ACCEPTED_WITH_TASK;
        }
        return null;
    }

    public String taskId() { return taskId; }
    public InvocationResponseStatus folded() { return folded; }
    public void release() { this.released = true; }
    public boolean isReleased() { return released; }
}
