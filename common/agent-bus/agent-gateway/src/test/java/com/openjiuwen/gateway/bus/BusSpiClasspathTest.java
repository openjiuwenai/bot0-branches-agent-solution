/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;

import org.junit.jupiter.api.Test;

/**
 * B0 smoke: the FEAT-013 forwarding SPI is on the agent-gateway classpath
 * (FEAT-012 BUS path). Behaviour slices (B1+) build on these types.
 *
 * @since 2026-07-24
 */
class BusSpiClasspathTest {
    @Test
    void forwardingSpiTypesAreReachable() {
        assertThat(AgentBusEventType.CLIENT_INVOCATION_REQUESTED).isNotNull();
        assertThat(AgentBusEventType.INVOCATION_ACCEPTED).isNotNull();
        assertThat(AgentBusEventType.INVOCATION_STREAM_READY).isNotNull();
        assertThat(InvocationResponseStatus.UNKNOWN).isNotNull();
        assertThat(InvocationResponseStatus.ACCEPTED_WITH_TASK).isNotNull();
    }
}
