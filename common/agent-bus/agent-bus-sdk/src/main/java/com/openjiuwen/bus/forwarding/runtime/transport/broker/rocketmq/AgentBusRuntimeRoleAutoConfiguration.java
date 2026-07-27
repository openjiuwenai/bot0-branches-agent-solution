/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq;

import com.openjiuwen.bus.forwarding.common.AgentBusBrokerProperties;
import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerClientProperties;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Runtime-role auto-configuration — the broker ports an agent-runtime acting as the
 * <b>target/callee</b> needs (FEAT-017 direct-tap). Activates only when the deployment
 * opts in via {@code agent-bus.role.runtime.enabled=true}, so a pure caller/gateway
 * deployment is NOT wired with the callee-side ports.
 *
 * <p>Owns the two runtime-role ports:
 * <ul>
 *   <li>{@code runtimeRequestConsumer} ({@link RocketMqBrokerForwardingConsumer} with
 *       {@link DefaultBrokerTopicResolver} + suffix {@code "deliver"} →
 *       {@code ascend_bus_*_deliver}) — the <b>deliver-topic consumer</b>, the doc's core
 *       ask: a runtime consumes the hop1 delivery requests addressed to it. NEW here
 *       (was not in {@code RocketMqBrokerClientConfiguration});</li>
 *   <li>{@code runtimeResponseProducer} ({@link RocketMqBrokerForwardingProducer} with
 *       {@link DefaultBrokerTopicResolver} + suffix {@code "resp_in"} →
 *       {@code ascend_bus_*_resp_in}) — direct-tap response PRODUCE. MOVED here from
 *       {@code RocketMqBrokerClientConfiguration} so the runtime role autoconfig owns it
 *       (the old config no longer creates it unconditionally); the role autoconfig
 *       creates it only when {@code role.runtime.enabled=true}. Declared as the
 *       {@link BrokerForwardingProducerPort} SPI type so the external runtime injects it
 *       by qualifier without seeing the concrete RocketMQ adapter.</li>
 * </ul>
 *
 * <p>Builds on the broker base ({@link AgentBusBrokerClientBaseAutoConfiguration}),
 * which provides {@link BrokerClientProperties}, {@link AgentBusBrokerProperties}, and
 * the shared {@code defaultProducer} ({@link DefaultMQProducer}). This autoconfig injects
 * them by parameter name; it does NOT own a {@code DataSource} (the JDBC reliability
 * layer is a separate concern). The consumer's {@link DefaultLitePullConsumer} is created
 * lazily at {@code subscribe} (group-bound), so bean creation here does not contact the
 * broker; the producer's sender is a non-blocking wrapper around the already-started
 * {@code defaultProducer}.
 *
 * <p><b>Package: {@code transport.broker.rocketmq}.</b> Both beans reference concrete
 * RocketMQ client types ({@code org.apache.rocketmq.*}); ArchUnit confines RocketMQ
 * types to this package (ADR-0163 Decision 4 + the forwarding SPI purity harness's
 * {@code transport.broker..} rocketmq-import exemption). {@link BrokerClientProperties}
 * / {@link AgentBusBrokerProperties} are broker-neutral and imported freely.
 *
 * <p>Registered as an {@link AutoConfiguration @AutoConfiguration} (not plain
 * {@code @Configuration}); a later task lists it in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Until then it is also meta-annotated with {@code @Configuration}, so it remains
 * discoverable by component scan when the imports metadata has not landed.
 *
 * <p>Authority: arch-driven role-based-assembly (runtime-role ports); FEAT-017
 * (direct-tap response produce); ADR-0163 (forwarding-reorg layering).
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent-bus.role.runtime", name = "enabled", havingValue = "true")
public class AgentBusRuntimeRoleAutoConfiguration {
    /**
     * Deliver-topic consumer — consumes from {@code ascend_bus_*_deliver} via
     * {@link DefaultBrokerTopicResolver} + suffix {@code "deliver"}. Used by an
     * agent-runtime acting as the <b>target/callee</b>: it drains the hop1 delivery
     * requests addressed to it. The underlying {@link DefaultLitePullConsumer} is created
     * lazily at {@code subscribe} (group-bound), so bean creation does not contact the
     * broker; {@code close} on context shutdown shuts the consumer (idempotent).
     *
     * @param broker the broker-neutral connection config (nameserver endpoints)
     * @param props  the broker properties (poll wait millis)
     * @return a deliver-topic consumer bound to the {@code deliver} suffix
     */
    @Bean(name = "runtimeRequestConsumer", destroyMethod = "close")
    BrokerForwardingConsumerPort runtimeRequestConsumer(BrokerClientProperties broker, AgentBusBrokerProperties props) {
        return new RocketMqBrokerForwardingConsumer(new DefaultBrokerTopicResolver(), "deliver",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(broker.nameserverEndpoints()),
                props.pollWaitMillis());
    }

    /**
     * Target-runtime response producer — produces to {@code ascend_bus_*_resp_in} via
     * {@link DefaultBrokerTopicResolver} + suffix {@code "resp_in"}. Used by an
     * agent-runtime acting as the <b>target/callee</b> (FEAT-017 direct-tap): its
     * response producer taps {@code AgentEmitter} callbacks and emits
     * {@code INVOCATION_*}/{@code A2A_CALL_*} response events. Declared as the
     * broker-agnostic {@link BrokerForwardingProducerPort} SPI type so the external
     * runtime injects it by qualifier and never sees the concrete RocketMQ adapter.
     *
     * <p>MOVED from {@code RocketMqBrokerClientConfiguration}; the runtime role autoconfig
     * owns it so it is created only when {@code role.runtime.enabled=true}.
     *
     * @param defaultProducer the shared RocketMQ producer (provides the send transport)
     * @return a response relay bound to the {@code resp_in} topic, exposed as the SPI type
     */
    @Bean(name = "runtimeResponseProducer")
    BrokerForwardingProducerPort runtimeResponseProducer(DefaultMQProducer defaultProducer) {
        return new RocketMqBrokerForwardingProducer(new DefaultBrokerTopicResolver(), "resp_in",
                RocketMqBrokerForwardingProducer.defaultSender(defaultProducer));
    }
}
