/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.relay;

import com.openjiuwen.bus.forwarding.common.AgentBusBrokerProperties;
import com.openjiuwen.bus.forwarding.runtime.persistence.jdbc.JdbcForwardingOutbox;
import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerClientProperties;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.RocketMqBrokerForwardingConsumer;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.RocketMqBrokerForwardingProducer;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingInboxPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Relay-role auto-configuration — the two-hop governance relay wiring
 * (arch-driven G5-B, decision-tree Q2a). Activates only when the deployment
 * opts in via {@code agent-bus.role.relay.enabled=true}, so a pure caller /
 * gateway / runtime deployment is NOT wired with the relay ports.
 *
 * <p>MOVED verbatim from the former {@code EventBusRelayConfiguration} (a
 * {@code @Profile("eventbus") @Configuration}); the profile gate is replaced by
 * the role property gate, the bean wiring is unchanged. Wires the two-hop
 * governance relay as TWO {@link EventBusRelayWorker} instances:
 * <ul>
 *   <li><b>forward relay</b> — consume hop1 req ({@code ascend_bus_*_req}) → inbox dedup /
 *       tenant / correlation / audit → re-publish hop2 deliver
 *       ({@code ascend_bus_*_deliver});</li>
 *   <li><b>response relay</b> — consume resp_in ({@code ascend_bus_*_resp_in}) → govern →
 *       re-publish resp_out ({@code ascend_bus_*_resp_out}).</li>
 * </ul>
 * The registry plane boots alongside (component-scanned {@code @Configuration} classes in
 * {@code registry.runtime} are already on the classpath). The durable outbox + inbox are
 * provided by {@link com.openjiuwen.bus.forwarding.common.AgentBusReliabilityAutoConfiguration}
 * (the JDBC reliability autoconfig, conditional on a {@code DataSource}); this autoconfig
 * injects them by type.
 *
 * <p><b>Relay consumer filters are tenant-only</b> ({@code DeliveryFilter(Map.of("tenantId", …))})
 * — the relay is the intermediary for its tenant, so it consumes every in-tenant message on
 * its hop-in topic (no {@code targetServiceId} pin; reqs are targeted at runtimes, responses
 * at callers — neither is the relay itself). This is the correct intermediary filter, distinct
 * from the gateway response consumer's targetServiceId-only filter (D13).
 *
 * <p><b>Shared infra moved out.</b> The {@code brokerClientProperties} /
 * {@code relayOutbox} / {@code relayInbox} beans previously declared in the old config are now
 * provided by the broker base ({@link 
 * com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.AgentBusBrokerClientBaseAutoConfiguration},
 * which owns {@code brokerClientProperties} + {@link AgentBusBrokerProperties}) and the
 * JDBC reliability autoconfig ({@link com.openjiuwen.bus.forwarding.common.AgentBusReliabilityAutoConfiguration},
 * which owns {@code forwardingOutbox} / {@code forwardingInbox}), shared with the gateway process
 * form. This autoconfig keeps ONLY the relay-role beans — the relay producer
 * (group={@code props.producerGroup() + "-relay"}, distinct from the gateway's
 * {@code props.producerGroup()}), the forward/response relay consumers + producers
 * (role-specific topic suffixes), the workers, the ticks, and the subscribe-at-startup.
 *
 * <p><b>The dedicated relay scheduler</b> ({@code relayTaskScheduler} + {@code relayScheduler},
 * which drive {@code forwardRelayTick} / {@code responseRelayTick} programmatically) stays in
 * the separate {@link EventBusRelaySchedulingConfig} (still {@code @Profile("eventbus")} — the
 * relay fat-jar activates it via the {@code eventbus} profile, kept as a compat alias alongside
 * {@code agent-bus.role.relay.enabled=true}). Both must be active together for a fully-wired
 * relay; the {@code eventbus} profile remains the CLI-driven activation
 * ({@code --spring.profiles.active=eventbus}), the property gates the role autoconfig.
 *
 * <p><b>Verification:</b> compile-verified (full suite green); producer {@code start()} +
 * subscribe-at-startup boot-correctness is verified by the two-hop IT (G5-E, env-guarded
 * against the real broker). The scheduler that periodically drives {@code runOnce} is
 * mechanism-verified by the {@code EventBusRelaySchedulerTest} slice (plain-JUnit, dedicated
 * ThreadPoolTaskScheduler + fake RelayTick); the full event-bus context boot vs real
 * broker+Postgres remains env-deferred. See
 * {@code docs/4plus1/delta/event-bus-relay/deviations.md}.
 *
 * <p>Registered as an {@link AutoConfiguration @AutoConfiguration} (not plain
 * {@code @Configuration}); a later task lists it in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}.
 * Until then it is meta-annotated with {@code @Configuration} (via {@link AutoConfiguration}),
 * so it remains discoverable by the relay app's component scan
 * ({@code @SpringBootApplication(scanBasePackages = "com.openjiuwen.bus")}) when the imports
 * metadata has not landed.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md §1.2 / §4.1 / §5.1};
 * {@code docs/4plus1/delta/event-bus-relay/decision-tree.md} Q1a/Q2a.
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "agent-bus.role.relay", name = "enabled", havingValue = "true")
public class AgentBusRelayRoleAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(AgentBusRelayRoleAutoConfiguration.class);

    /**
     * Relay RocketMQ producer (hop2 deliver + resp_out produce). Group is suffixed
     * "-relay" to distinguish from the gateway producer.
     *
     * @param broker the broker client properties (nameserver endpoints)
     * @param props the agent-bus broker properties (producer group)
     * @return a started {@link DefaultMQProducer} suffixed with "-relay"
     * @throws Exception if the producer fails to start
     */
    @Bean(destroyMethod = "shutdown")
    DefaultMQProducer relayProducer(BrokerClientProperties broker, AgentBusBrokerProperties props) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer(props.producerGroup() + "-relay");
        producer.setNamesrvAddr(broker.nameserverEndpoints());
        producer.start();
        return producer;
    }

    @Bean(name = "forwardRelayConsumer", destroyMethod = "close")
    BrokerForwardingConsumerPort forwardRelayConsumer(BrokerClientProperties broker, AgentBusBrokerProperties props) {
        return new RocketMqBrokerForwardingConsumer(new DefaultBrokerTopicResolver(), "req",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(broker.nameserverEndpoints()),
                props.pollWaitMillis());
    }

    @Bean(name = "forwardRelayProducer")
    BrokerForwardingProducerPort forwardRelayProducer(DefaultMQProducer relayProducer) {
        return new RocketMqBrokerForwardingProducer(new DefaultBrokerTopicResolver(), "deliver",
                RocketMqBrokerForwardingProducer.defaultSender(relayProducer));
    }

    @Bean(name = "forwardRelayWorker")
    EventBusRelayWorker forwardRelayWorker(@Qualifier("forwardRelayConsumer") BrokerForwardingConsumerPort consumer,
                                           ForwardingInboxPort inbox, JdbcForwardingOutbox outbox,
                                           @Qualifier("forwardRelayProducer") BrokerForwardingProducerPort relay,
                                           AgentBusBrokerProperties props) {
        return new EventBusRelayWorker(consumer, inbox, outbox, outbox, relay,
                props.eventBusServiceId(), props.eventBusServiceId(), props.leaseDurationMs(),
                EventBusRelayWorker.FORWARD_REQUEST_TYPES);
    }

    @Bean(name = "responseRelayConsumer", destroyMethod = "close")
    BrokerForwardingConsumerPort responseRelayConsumer(BrokerClientProperties broker, AgentBusBrokerProperties props) {
        return new RocketMqBrokerForwardingConsumer(new DefaultBrokerTopicResolver(), "resp_in",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(broker.nameserverEndpoints()),
                props.pollWaitMillis());
    }

    @Bean(name = "responseRelayProducer")
    BrokerForwardingProducerPort responseRelayProducer(DefaultMQProducer relayProducer) {
        return new RocketMqBrokerForwardingProducer(new DefaultBrokerTopicResolver(), "resp_out",
                RocketMqBrokerForwardingProducer.defaultSender(relayProducer));
    }

    @Bean(name = "responseRelayWorker")
    EventBusRelayWorker responseRelayWorker(@Qualifier("responseRelayConsumer") BrokerForwardingConsumerPort consumer,
                                            ForwardingInboxPort inbox, JdbcForwardingOutbox outbox,
                                            @Qualifier("responseRelayProducer") BrokerForwardingProducerPort relay,
                                            AgentBusBrokerProperties props) {
        return new EventBusRelayWorker(consumer, inbox, outbox, outbox, relay,
                props.eventBusServiceId() + "-resp", props.eventBusServiceId(), props.leaseDurationMs(),
                EventBusRelayWorker.RESPONSE_TYPES);
    }

    /**
     * {@link RelayTick} seam bound to the forward relay worker (hop1 req -> hop2 deliver).
     *
     * @param forwardRelayWorker the forward relay worker (hop1 req -> hop2 deliver)
     * @return a {@link RelayTick} that delegates to the forward relay worker's runOnce
     */
    @Bean(name = "forwardRelayTick")
    RelayTick forwardRelayTick(@Qualifier("forwardRelayWorker") EventBusRelayWorker forwardRelayWorker) {
        return forwardRelayWorker::runOnce;
    }

    /**
     * {@link RelayTick} seam bound to the response relay worker (resp_in -> resp_out).
     *
     * @param responseRelayWorker the response relay worker (resp_in -> resp_out)
     * @return a {@link RelayTick} that delegates to the response relay worker's runOnce
     */
    @Bean(name = "responseRelayTick")
    RelayTick responseRelayTick(@Qualifier("responseRelayWorker") EventBusRelayWorker responseRelayWorker) {
        return responseRelayWorker::runOnce;
    }

    /**
     * Subscribe-at-startup: both relay consumers register on their hop-in topics with a
     * <b>tenant-only</b> filter (the intermediary consumes every in-tenant message on its
     * hop-in topic). FIXME (G5-E): the {@code tenant} scope is a single configured value
     * (single-tenant-per-deployment); a multi-tenant relay subscribe is a refinement.
     *
     * @param forwardConsumer  the forward relay consumer (hop1 req in)
     * @param responseConsumer the response relay consumer (resp_in in)
     * @param props            the agent-bus broker properties (tenant + service ids)
     * @return a {@link SmartLifecycle} that subscribes both relay consumers at startup
     */
    @Bean
    SmartLifecycle relaySubscriptions(
            @Qualifier("forwardRelayConsumer") BrokerForwardingConsumerPort forwardConsumer,
            @Qualifier("responseRelayConsumer") BrokerForwardingConsumerPort responseConsumer,
            AgentBusBrokerProperties props) {
        return new SmartLifecycle() {
            private boolean started;

            @Override
            public synchronized void start() {
                String eventBus = props.eventBusServiceId();
                String tenant = props.tenant();
                DeliveryFilter tenantFilter = new DeliveryFilter(Map.of("tenantId", tenant));
                // event types "invocation" + "a2a" families → DefaultBrokerTopicResolver resolves each
                // to its hop-in topic (ascend_bus_<family>_req / _resp_in). A representative request
                // / response event type per family carries the family into the resolver.
                forwardConsumer.subscribe(eventBus, AgentBusEventType.CLIENT_INVOCATION_REQUESTED, tenantFilter);
                forwardConsumer.subscribe(eventBus, AgentBusEventType.A2A_CALL_REQUESTED, tenantFilter);
                responseConsumer.subscribe(eventBus + "-resp",
                        AgentBusEventType.INVOCATION_RESPONSE, tenantFilter);
                responseConsumer.subscribe(eventBus + "-resp", AgentBusEventType.A2A_CALL_RESPONSE, tenantFilter);
                log.info("SUBSCRIBE forward relay: consumerGroup={} "
                        + "topics=[ascend_bus_invocation_req, ascend_bus_a2a_req] "
                        + "filter=tenantId={}", eventBus, tenant);
                log.info("SUBSCRIBE response relay: consumerGroup={} "
                        + "topics=[ascend_bus_invocation_resp_in, ascend_bus_a2a_resp_in] "
                        + "filter=tenantId={}", eventBus + "-resp", tenant);
                started = true;
            }

            @Override
            public synchronized void stop() {
                started = false;
            }

            @Override
            public boolean isRunning() {
                return started;
            }

            @Override
            public int getPhase() {
                return Integer.MIN_VALUE + 100;
            }
        };
    }
}
