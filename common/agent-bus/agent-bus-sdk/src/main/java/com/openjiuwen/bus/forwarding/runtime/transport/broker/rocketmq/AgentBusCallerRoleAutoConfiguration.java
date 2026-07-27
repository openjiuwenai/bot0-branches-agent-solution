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
 * Caller-role auto-configuration — the broker ports any process form needs when it acts
 * as a <b>caller</b> (produces hop1 requests + consumes hop responses). Activates only
 * when the deployment opts in via {@code agent-bus.role.caller.enabled=true}, so a pure
 * target/callee runtime-role deployment is NOT wired with the caller-side ports.
 *
 * <p>Owns the two caller-role ports:
 * <ul>
 *   <li>{@code requestProducer} ({@link RocketMqBrokerForwardingProducer} with
 *       {@link DefaultBrokerTopicResolver} + suffix {@code "req"} →
 *       {@code ascend_bus_*_req}) — hop1 request produce. Used by the gateway AND by any
 *       agent-runtime acting as a caller (invoking another agent). MOVED here from
 *       {@code RocketMqBrokerClientConfiguration} so the caller role autoconfig owns it
 *       (the old config no longer creates it unconditionally); the role autoconfig
 *       creates it only when {@code role.caller.enabled=true};</li>
 *   <li>{@code responseConsumer} ({@link RocketMqBrokerForwardingConsumer} with
 *       {@link DefaultBrokerTopicResolver} + suffix {@code "resp_out"} →
 *       {@code ascend_bus_*_resp_out}) — response consume. Used by the gateway AND by any
 *       agent-runtime awaiting a response to its own outbound call. MOVED here from
 *       {@code RocketMqBrokerClientConfiguration}.</li>
 * </ul>
 *
 * <p>Builds on the broker base ({@link AgentBusBrokerClientBaseAutoConfiguration}),
 * which provides {@link BrokerClientProperties}, {@link AgentBusBrokerProperties}, and
 * the shared {@code defaultProducer} ({@link DefaultMQProducer}). This autoconfig injects
 * them by parameter name; it does NOT own a {@code DataSource} (the JDBC reliability
 * layer is a separate concern). The consumer's {@code DefaultLitePullConsumer} is created
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
 * <p><b>Why caller-role, not gateway-specific.</b> The prior name
 * ({@code RocketMqGatewayBrokerConfiguration}) + bean names
 * ({@code gatewayProducer} / {@code gatewayRelay} / {@code gatewayResponseConsumer})
 * implied gateway-only ownership — but the only gateway-specific thing here is the
 * caller role. Any agent-runtime that calls another agent (e.g. an orchestration
 * runtime invoking a tool agent) produces the same hop1 {@code req} topic and consumes
 * the same {@code resp_out} topic. Renaming to {@code requestProducer} /
 * {@code responseConsumer} makes that explicit and lets an agent-runtime reuse these
 * beans instead of re-declaring them.
 *
 * <p>Authority: arch-driven role-based-assembly (caller-role ports); ADR-0163
 * (forwarding-reorg layering).
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent-bus.role.caller", name = "enabled", havingValue = "true")
public class AgentBusCallerRoleAutoConfiguration {
    /**
     * Hop1 request relay — produces to {@code ascend_bus_*_req} via
     * {@link DefaultBrokerTopicResolver} + suffix {@code "req"}. Used by the gateway AND by any
     * agent-runtime acting as a caller (invoking another agent).
     *
     * @param defaultProducer the base RocketMQ producer to send hop1 requests
     * @return a hop1 request relay bound to the {@code req} topic
     */
    @Bean(name = "requestProducer")
    BrokerForwardingProducerPort requestProducer(DefaultMQProducer defaultProducer) {
        return new RocketMqBrokerForwardingProducer(new DefaultBrokerTopicResolver(), "req",
                RocketMqBrokerForwardingProducer.defaultSender(defaultProducer));
    }

    /**
     * Response consumer — consumes from {@code ascend_bus_*_resp_out} via
     * {@link DefaultBrokerTopicResolver} + suffix {@code "resp_out"}. Used by the gateway AND by any
     * agent-runtime awaiting a response to its own outbound call.
     *
     * @param broker the broker-neutral connection config (nameserver endpoints)
     * @param props the broker properties (poll wait millis)
     * @return a response consumer bound to the {@code resp_out} topic
     */
    @Bean(name = "responseConsumer", destroyMethod = "close")
    BrokerForwardingConsumerPort responseConsumer(BrokerClientProperties broker, AgentBusBrokerProperties props) {
        return new RocketMqBrokerForwardingConsumer(new DefaultBrokerTopicResolver(), "resp_out",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(broker.nameserverEndpoints()),
                props.pollWaitMillis());
    }
}
