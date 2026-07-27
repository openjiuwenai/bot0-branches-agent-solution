/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus.wait;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FiveStateFolder}.
 *
 * @since 2026-07-24
 */
class FiveStateFolderTest {
    @Test
    void responseToCompleted() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_RESPONSE))
                .isEqualTo(InvocationResponseStatus.COMPLETED_RESPONSE);
    }

    @Test
    void acceptedToAcceptedWithTask() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_ACCEPTED))
                .isEqualTo(InvocationResponseStatus.ACCEPTED_WITH_TASK);
    }

    @Test
    void rejectedToRejected() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_REJECTED))
                .isEqualTo(InvocationResponseStatus.REJECTED);
    }

    @Test
    void failedToFailed() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_FAILED))
                .isEqualTo(InvocationResponseStatus.FAILED);
    }

    @Test
    void streamReadyToStreamReady() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_STREAM_READY))
                .isEqualTo(InvocationResponseStatus.STREAM_READY);
    }

    @Test
    void terminalToCompleted() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_TERMINAL))
                .isEqualTo(InvocationResponseStatus.COMPLETED_RESPONSE);
    }

    @Test
    void isTerminalCorrect() {
        assertThat(FiveStateFolder.isTerminal(InvocationResponseStatus.COMPLETED_RESPONSE)).isTrue();
        assertThat(FiveStateFolder.isTerminal(InvocationResponseStatus.REJECTED)).isTrue();
        assertThat(FiveStateFolder.isTerminal(InvocationResponseStatus.FAILED)).isTrue();
        assertThat(FiveStateFolder.isTerminal(InvocationResponseStatus.ACCEPTED_WITH_TASK)).isFalse();
        assertThat(FiveStateFolder.isTerminal(InvocationResponseStatus.UNKNOWN)).isFalse();
    }
}
