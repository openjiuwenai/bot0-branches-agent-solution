package com.openjiuwen.gateway.bus.wait;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;

class StreamReadyGateTest {
    @Test void streamReadyFolded() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_STREAM_READY))
                .isEqualTo(InvocationResponseStatus.STREAM_READY);
    }
    @Test void acceptedNotStreamReady() {
        assertThat(FiveStateFolder.fold(AgentBusEventType.INVOCATION_ACCEPTED))
                .isNotEqualTo(InvocationResponseStatus.STREAM_READY);
    }
    @Test void streamReadyNotTerminal() {
        assertThat(FiveStateFolder.isTerminal(InvocationResponseStatus.STREAM_READY)).isFalse();
    }
}
