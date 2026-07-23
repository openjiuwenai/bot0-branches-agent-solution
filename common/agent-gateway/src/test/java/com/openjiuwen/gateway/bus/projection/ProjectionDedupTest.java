package com.openjiuwen.gateway.projection;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

class ProjectionDedupTest {
    @Test void duplicateProjectionIgnored() {
        var t = new ProjectionTracker();
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_ACCEPTED, "digest")).isTrue();
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_ACCEPTED, "digest")).isFalse();
    }
    @Test void lateProjectionAfterTerminalIgnored() {
        var t = new ProjectionTracker();
        t.shouldProcess("c1", AgentBusEventType.INVOCATION_RESPONSE, "d1"); // terminal
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_ACCEPTED, "d2")).isFalse();
    }
    @Test void responseBeforeAcceptedFolds() {
        var t = new ProjectionTracker();
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_RESPONSE, "d1")).isTrue();
    }
    @Test void streamReadyBeforeAcceptedAllowed() {
        var t = new ProjectionTracker();
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_STREAM_READY, "d1")).isTrue();
        assertThat(t.isTerminalClosed()).isFalse();
    }
}
