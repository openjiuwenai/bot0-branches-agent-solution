/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.wait;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for STREAM_READY fold behaviour.
 *
 * @since 2026-07-24
 */
class StreamReadyGateTest {

    @Test
    void streamReadyFolded() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_STREAM_READY))
                .isEqualTo(InvocationResponseStatus.STREAM_READY);
    }

    @Test
    void acceptedNotStreamReady() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_ACCEPTED))
                .isNotEqualTo(InvocationResponseStatus.STREAM_READY);
    }

    @Test
    void streamReadyNotTerminal() {
        assertThat(FiveStateFolder.isTerminal(InvocationResponseStatus.STREAM_READY)).isFalse();
    }
}
