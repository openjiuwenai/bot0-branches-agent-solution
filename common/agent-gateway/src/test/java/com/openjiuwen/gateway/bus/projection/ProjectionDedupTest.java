/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProjectionTracker} dedup and terminal closure.
 *
 * @since 2026-07-24
 */
class ProjectionDedupTest {

    @Test
    void duplicateProjectionIgnored() {
        var t = new ProjectionTracker();
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_ACCEPTED, "digest")).isTrue();
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_ACCEPTED, "digest")).isFalse();
    }

    @Test
    void lateProjectionAfterTerminalIgnored() {
        var t = new ProjectionTracker();
        t.shouldProcess("c1", AgentBusEventType.INVOCATION_RESPONSE, "d1");
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_ACCEPTED, "d2")).isFalse();
    }

    @Test
    void responseBeforeAcceptedFolds() {
        var t = new ProjectionTracker();
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_RESPONSE, "d1")).isTrue();
    }

    @Test
    void streamReadyBeforeAcceptedAllowed() {
        var t = new ProjectionTracker();
        assertThat(t.shouldProcess("c1", AgentBusEventType.INVOCATION_STREAM_READY, "d1")).isTrue();
        assertThat(t.isTerminalClosed()).isFalse();
    }
}
