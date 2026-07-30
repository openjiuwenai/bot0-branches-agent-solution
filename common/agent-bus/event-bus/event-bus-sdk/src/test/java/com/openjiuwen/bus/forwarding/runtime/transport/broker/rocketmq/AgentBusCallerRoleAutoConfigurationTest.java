/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerClientProperties;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * TDD for the Task 4 caller-role autoconfig (role-based-assembly). Pins the conditional
 * wiring so a deployment opts into the caller-role ports (hop1 {@code req} produce +
 * {@code resp_out} response consume) via {@code agent-bus.role.caller.enabled=true};
 * without the flag, neither caller port is created.
 *
 * <ul>
 *   <li><b>Test A</b> — base + caller autoconfigs + {@code role.caller.enabled=true}:
 *       {@link BrokerForwardingProducerPort} + {@link BrokerForwardingConsumerPort} are
 *       PRESENT (the request relay + response consumer are wired);</li>
 *   <li><b>Test B</b> — base + caller autoconfigs, NO flag: both caller ports are ABSENT
 *       (the {@code @ConditionalOnProperty} disables the caller autoconfig; the broker
 *       base still boots).</li>
 * </ul>
 *
 * <p><b>Test harness notes.</b> Uses Spring Boot's {@link ApplicationContextRunner} +
 * {@link AutoConfigurations} (no full {@code @SpringBootTest}). Only the two autoconfigs
 * are registered, so {@code RocketMqBrokerClientConfiguration} (a plain
 * {@code @Configuration}, not in the imports metadata) is NOT processed here — this
 * isolates the caller role ports and guarantees no duplicate {@code requestProducer} /
 * {@code responseConsumer} beans. The base {@code defaultProducer} starts a real
 * {@link DefaultMQProducer} against an unreachable nameserver ({@code localhost:1});
 * RocketMQ's {@code start()} is non-blocking — it kicks off background netty/scheduled
 * services without contacting the nameserver, so the bean is created and later shut
 * down by {@code destroyMethod="shutdown"} on context close. The caller consumer's
 * {@code DefaultLitePullConsumer} is created lazily at {@code subscribe} (group-bound),
 * so the {@code responseConsumer} bean is created without contacting the broker.
 *
 * @since 0.1.0
 */
class AgentBusCallerRoleAutoConfigurationTest {
    /** Required broker properties (nameserver/namespace/producer-group are validated non-blank). */
    private static final String[] BASE_PROPS = {
            "agent-bus.nameserver=localhost:1",
            "agent-bus.namespace=test-ns",
            "agent-bus.producer-group=test-producer-group"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    /**
     * Test A — caller role enabled: the request relay + response consumer are present.
     */
    @Test
    void callerRoleEnabledCreatesProducerAndConsumerBeans() {
        runner.withConfiguration(AutoConfigurations.of(
                        AgentBusBrokerClientBaseAutoConfiguration.class,
                        AgentBusCallerRoleAutoConfiguration.class))
                .withPropertyValues(BASE_PROPS)
                .withPropertyValues("agent-bus.role.caller.enabled=true")
                .run(context -> {
                    // caller role ports present
                    assertThat(context).hasSingleBean(BrokerForwardingProducerPort.class);
                    assertThat(context).hasSingleBean(BrokerForwardingConsumerPort.class);
                    // broker base still present
                    assertThat(context).hasSingleBean(BrokerClientProperties.class);
                    assertThat(context).hasSingleBean(DefaultMQProducer.class);
                });
    }

    /**
     * Test B — caller role disabled (no flag): neither caller port is created; base still boots.
     */
    @Test
    void callerRoleDisabledCreatesNoRoleBeans() {
        runner.withConfiguration(AutoConfigurations.of(
                        AgentBusBrokerClientBaseAutoConfiguration.class,
                        AgentBusCallerRoleAutoConfiguration.class))
                .withPropertyValues(BASE_PROPS)
                .run(context -> {
                    // caller role ports absent (autoconfig disabled by @ConditionalOnProperty)
                    assertThat(context).doesNotHaveBean(BrokerForwardingProducerPort.class);
                    assertThat(context).doesNotHaveBean(BrokerForwardingConsumerPort.class);
                    // broker base still present
                    assertThat(context).hasSingleBean(BrokerClientProperties.class);
                    assertThat(context).hasSingleBean(DefaultMQProducer.class);
                });
    }
}
