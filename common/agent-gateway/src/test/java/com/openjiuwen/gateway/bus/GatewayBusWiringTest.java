/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.gateway.bus.control.BrokerProjectionFeed;
import com.openjiuwen.gateway.bus.control.FakeBrokerForwardingConsumerPort;
import com.openjiuwen.gateway.bus.control.FakeBrokerForwardingProducerPort;
import com.openjiuwen.gateway.bus.control.FakeForwardingOutboxPort;
import com.openjiuwen.gateway.bus.control.ProjectionFeed;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Bus-mode context wiring: {@code GatewayBusConfiguration} produces the BUS path beans
 * ({@link BusForwarder}, {@link BrokerProjectionFeed} as {@link ProjectionFeed}) and the
 * projection feed subscribes to the INVOCATION_* family at startup (FEAT-012 §4.5 I-04).
 *
 * <p>Fakes stand in for the SDK {@code responseConsumer} / {@code forwardingOutbox} ports
 * (the gateway module is SPI-only; the real beans arrive via the launcher's classpath).
 *
 * @since 2026-07-27
 */
@SpringBootTest(properties = "gateway.path-mode=bus")
@Import({FakeForwardingOutboxPort.class, FakeBrokerForwardingConsumerPort.class, FakeBrokerForwardingProducerPort.class})
class GatewayBusWiringTest {
    @Autowired
    private BusForwarder busForwarder;
    @Autowired
    private ProjectionFeed projectionFeed;
    @Autowired
    private FakeBrokerForwardingConsumerPort consumer;

    @Test
    void busBeansWired() {
        assertThat(busForwarder).isNotNull();
        assertThat(projectionFeed).isInstanceOf(BrokerProjectionFeed.class);
    }

    @Test
    void projectionFeedSubscribesInvocationFamilyAtStart() {
        assertThat(consumer.subscriptions())
                .anyMatch(s -> s.eventType().name().startsWith("INVOCATION_"));
        assertThat(consumer.subscriptions())
                .noneMatch(s -> s.eventType().name().startsWith("A2A_"));
    }
}
