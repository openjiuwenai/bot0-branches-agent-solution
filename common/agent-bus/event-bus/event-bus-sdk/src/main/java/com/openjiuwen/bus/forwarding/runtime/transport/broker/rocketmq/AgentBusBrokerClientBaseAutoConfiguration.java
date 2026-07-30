/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq;

import com.openjiuwen.bus.forwarding.common.AgentBusBrokerProperties;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerClientProperties;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Broker-client BASE auto-configuration — the role-agnostic, <b>NO JDBC</b> foundation
 * any process form that connects to the broker needs. The caller/callee role
 * auto-configurations (built in later tasks) assemble their ports on top of this.
 *
 * <p>Owns the broker base previously provided by the (now split) shared
 * {@code AgentBusInfrastructureConfiguration}:
 * <ul>
 *   <li>{@link AgentBusBrokerProperties} — enabled HERE (via
 *       {@link EnableConfigurationProperties}) so every role autoconfig injects it
 *       without re-enabling it;</li>
 *   <li>{@link BrokerClientProperties} — broker-neutral connection config (the SPI
 *       record carries no RocketMQ / Kafka types);</li>
 *   <li>{@code defaultProducer} ({@link DefaultMQProducer}, group=
 *       {@code props.producerGroup()}) — the shared base producer group. MOVED here
 *       from {@code RocketMqBrokerClientConfiguration} so the BASE owns the shared
 *       producer independently of the role ports; the role ports
 *       ({@code requestProducer} / {@code responseConsumer} / {@code runtimeResponseProducer})
 *       now inject this bean by qualifier and stay in
 *       {@code RocketMqBrokerClientConfiguration} (Task 3/4/5 relocates the ports).</li>
 * </ul>
 *
 * <p><b>No JDBC here.</b> This autoconfiguration does NOT depend on a
 * {@code DataSource} — a caller-role application that has no {@code DataSource} still
 * gets this broker base so its role autoconfigs can build their ports, WITHOUT the
 * JDBC outbox/inbox (or Flyway) being created. The JDBC reliability layer lives in
 * {@link com.openjiuwen.bus.forwarding.common.AgentBusReliabilityAutoConfiguration},
 * conditional on {@code DataSource} being on the classpath.
 *
 * <p><b>Package: {@code transport.broker.rocketmq}.</b> {@code defaultProducer} is a
 * concrete RocketMQ client bean ({@code org.apache.rocketmq.*}); ArchUnit confines
 * RocketMQ types to this package (ADR-0163 Decision 4 + the forwarding SPI purity
 * harness's {@code transport.broker..} rocketmq-import exemption).
 * {@link BrokerClientProperties} / {@link AgentBusBrokerProperties} are broker-neutral
 * and imported freely. This is why the base — not just the role ports — lives here
 * rather than in {@code forwarding.common}.
 *
 * <p>Registered as an {@link AutoConfiguration @AutoConfiguration} so a later task
 * lists it in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports};
 * it is also meta-annotated with {@code @Configuration}, so it remains discoverable
 * by component scan until that metadata lands.
 *
 * <p>Authority: arch-driven role-based-assembly (broker-client base / JDBC-reliability
 * split); ADR-0163 (forwarding-reorg layering).
 *
 * @since 0.1.0
 */
@AutoConfiguration
@EnableConfigurationProperties(AgentBusBrokerProperties.class)
public class AgentBusBrokerClientBaseAutoConfiguration {
    /**
     * Generic broker connection config — broker-neutral (no RocketMQ / Kafka types).
     *
     * @param props the broker properties (nameserver, namespace)
     * @return a broker-neutral connection config
     */
    @Bean
    BrokerClientProperties brokerClientProperties(AgentBusBrokerProperties props) {
        return new BrokerClientProperties(props.nameserver(), props.namespace());
    }

    /**
     * Default RocketMQ producer — base group ({@code props.producerGroup()}), used by
     * the role ports ({@code requestProducer} / {@code runtimeResponseProducer}) for
     * hop1 request produce and direct-tap response produce. Any caller (gateway or
     * agent-runtime) can inject this for direct produce. Lifecycle: start on bean
     * creation, shutdown on close.
     *
     * @param broker the broker-neutral connection config (nameserver endpoints)
     * @param props the broker properties (producer group)
     * @return a started DefaultMQProducer with the configured group and nameserver
     * @throws Exception if the producer fails to start
     */
    @Bean(destroyMethod = "shutdown")
    DefaultMQProducer defaultProducer(BrokerClientProperties broker, AgentBusBrokerProperties props) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(props.producerGroup());
        producer.setNamesrvAddr(broker.nameserverEndpoints());
        producer.start();
        return producer;
    }
}
