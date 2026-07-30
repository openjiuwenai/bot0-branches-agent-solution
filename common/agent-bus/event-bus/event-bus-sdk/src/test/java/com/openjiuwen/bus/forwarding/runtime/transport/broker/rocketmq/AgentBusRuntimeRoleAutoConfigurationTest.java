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
 * TDD for the Task 3 runtime-role autoconfig (role-based-assembly). Pins the conditional
 * wiring so a deployment opts into the target/callee runtime-role ports (deliver-topic
 * consume + direct-tap {@code resp_in} produce) via
 * {@code agent-bus.role.runtime.enabled=true}; without the flag, neither role port is
 * created.
 *
 * <ul>
 *   <li><b>Test A</b> — base + runtime autoconfigs + {@code role.runtime.enabled=true}:
 *       {@link BrokerForwardingConsumerPort} + {@link BrokerForwardingProducerPort} are
 *       PRESENT (the deliver consumer + resp_in producer are wired);</li>
 *   <li><b>Test B</b> — base + runtime autoconfigs, NO flag: both role ports are ABSENT
 *       (the {@code @ConditionalOnProperty} disables the runtime autoconfig; the broker
 *       base still boots).</li>
 * </ul>
 *
 * <p><b>Test harness notes.</b> Uses Spring Boot's {@link ApplicationContextRunner} +
 * {@link AutoConfigurations} (no full {@code @SpringBootTest}). Only the two autoconfigs
 * are registered, so {@code RocketMqBrokerClientConfiguration} (a plain
 * {@code @Configuration}, not in the imports metadata) is NOT processed here — this
 * isolates the runtime role ports and guarantees no duplicate {@code requestProducer} /
 * {@code responseConsumer} beans. The base {@code defaultProducer} starts a real
 * {@link DefaultMQProducer} against an unreachable nameserver ({@code localhost:1});
 * RocketMQ's {@code start()} is non-blocking — it kicks off background netty/scheduled
 * services without contacting the nameserver, so the bean is created and later shut
 * down by {@code destroyMethod="shutdown"} on context close. The runtime consumer's
 * {@code DefaultLitePullConsumer} is created lazily at {@code subscribe} (group-bound),
 * so the {@code runtimeRequestConsumer} bean is created without contacting the broker.
 *
 * @since 0.1.0
 */
class AgentBusRuntimeRoleAutoConfigurationTest {
    /** Required broker properties (nameserver/namespace/producer-group are validated non-blank). */
    private static final String[] BASE_PROPS = {
            "agent-bus.nameserver=localhost:1",
            "agent-bus.namespace=test-ns",
            "agent-bus.producer-group=test-producer-group"
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner();

    /**
     * Test A — runtime role enabled: the deliver consumer + resp_in producer are present.
     */
    @Test
    void runtimeRoleEnabledCreatesConsumerAndProducerBeans() {
        runner.withConfiguration(AutoConfigurations.of(
                        AgentBusBrokerClientBaseAutoConfiguration.class,
                        AgentBusRuntimeRoleAutoConfiguration.class))
                .withPropertyValues(BASE_PROPS)
                .withPropertyValues("agent-bus.role.runtime.enabled=true")
                .run(context -> {
                    // runtime role ports present
                    assertThat(context).hasSingleBean(BrokerForwardingConsumerPort.class);
                    assertThat(context).hasSingleBean(BrokerForwardingProducerPort.class);
                    // broker base still present
                    assertThat(context).hasSingleBean(BrokerClientProperties.class);
                    assertThat(context).hasSingleBean(DefaultMQProducer.class);
                });
    }

    /**
     * Test B — runtime role disabled (no flag): neither role port is created; base still boots.
     */
    @Test
    void runtimeRoleDisabledCreatesNoRoleBeans() {
        runner.withConfiguration(AutoConfigurations.of(
                        AgentBusBrokerClientBaseAutoConfiguration.class,
                        AgentBusRuntimeRoleAutoConfiguration.class))
                .withPropertyValues(BASE_PROPS)
                .run(context -> {
                    // runtime role ports absent (autoconfig disabled by @ConditionalOnProperty)
                    assertThat(context).doesNotHaveBean(BrokerForwardingConsumerPort.class);
                    assertThat(context).doesNotHaveBean(BrokerForwardingProducerPort.class);
                    // broker base still present
                    assertThat(context).hasSingleBean(BrokerClientProperties.class);
                    assertThat(context).hasSingleBean(DefaultMQProducer.class);
                });
    }
}
