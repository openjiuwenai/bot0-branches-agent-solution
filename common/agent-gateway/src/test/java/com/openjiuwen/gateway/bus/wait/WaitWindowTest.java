package com.openjiuwen.gateway.bus.wait;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;

class WaitWindowTest {
    @Test void acceptWindowTimeoutToUnknown() {
        var w = new WaitWindow(0L, 30_000L, 60_000L);
        assertThat(w.checkTimeout(31_000L)).isEqualTo(InvocationResponseStatus.UNKNOWN);
    }
    @Test void responseWindowTimeoutToAccepted() {
        var w = new WaitWindow(0L, 30_000L, 60_000L);
        w.onProjection(InvocationResponseStatus.ACCEPTED_WITH_TASK, "task-1", 10_000L);
        assertThat(w.checkTimeout(71_000L)).isEqualTo(InvocationResponseStatus.ACCEPTED_WITH_TASK);
    }
    @Test void acceptedThenResponseWithinWindowToCompleted() {
        var w = new WaitWindow(0L, 30_000L, 60_000L);
        w.onProjection(InvocationResponseStatus.ACCEPTED_WITH_TASK, "task-1", 5_000L);
        w.onProjection(InvocationResponseStatus.COMPLETED_RESPONSE, null, 10_000L);
        assertThat(w.checkTimeout(15_000L)).isEqualTo(InvocationResponseStatus.COMPLETED_RESPONSE);
    }
    @Test void releasedWindowReturnsNull() {
        var w = new WaitWindow(0L, 30_000L, 60_000L);
        w.release();
        assertThat(w.checkTimeout(100_000L)).isNull();
    }
}
