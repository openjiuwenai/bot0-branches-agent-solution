/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.autoconfigure;

import com.openjiuwen.bus.forwarding.common.AgentBusBrokerProperties;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.service.bus.consumer.BusTaskProjectionCoordinator;
import com.openjiuwen.service.bus.consumer.RuntimeBusEventConsumer;
import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.a2a.TaskStoreProjectionPostProcessor;
import com.openjiuwen.service.bus.consumer.relay.BusProjectionRepairScheduler;
import com.openjiuwen.service.bus.consumer.relay.BusProjectionRepairer;
import com.openjiuwen.service.bus.consumer.relay.BusResponseRelay;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusBrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusResponsePublisher;
import com.openjiuwen.service.bus.consumer.runtime.BrokerConsumerLifecycle;
import com.openjiuwen.service.bus.consumer.runtime.BrokerDeliveryLoop;
import com.openjiuwen.service.bus.consumer.runtime.BusConcurrencyGuard;
import com.openjiuwen.service.bus.consumer.runtime.BusExecutors;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.stream.StreamReadyProjector;
import com.openjiuwen.service.bus.consumer.stream.StreamReferenceService;
import com.openjiuwen.service.bus.consumer.stream.StreamReferenceSubscriptionAspect;
import com.openjiuwen.service.bus.consumer.validation.BusEnvelopeValidator;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * Auto-configures the FEAT-017 bus consumer and its A2A bridge.
 *
 * @since 2026-07-22
 */
@AutoConfiguration
@AutoConfigureBefore(name = "com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration")
@EnableConfigurationProperties({BusConsumerProperties.class, AgentBusBrokerProperties.class})
@EnableAspectJAutoProxy
@ConditionalOnProperty(prefix = "openjiuwen.service.bus.consumer", name = "enabled", havingValue = "true")
public class BusConsumerAutoConfiguration {
    private static final String SERVICE_ID_PROPERTY = "openjiuwen.service.service-id";
    private static final long STREAM_REF_TTL_SECONDS = 60L * 60L;

    @Bean
    BusEnvelopeValidator busEnvelopeValidator(Environment environment, AgentBusBrokerProperties bus) {
        return new BusEnvelopeValidator(Clock.systemUTC(), tenantId(bus), serviceId(environment));
    }

    @Bean
    StreamReferenceService streamReferenceService() {
        return new StreamReferenceService(STREAM_REF_TTL_SECONDS);
    }

    @Bean
    StreamReferenceSubscriptionAspect streamReferenceSubscriptionAspect(StreamReferenceService references,
            InMemoryBusTaskAdmissionStore admissions, AgentBusBrokerProperties bus) {
        return new StreamReferenceSubscriptionAspect(references, admissions, tenantId(bus), Clock.systemUTC());
    }

    @Bean
    InMemoryBusTaskAdmissionStore busTaskAdmissionStore() {
        return new InMemoryBusTaskAdmissionStore();
    }

    @Bean
    InMemoryBusResponseProjectionStore busResponseProjectionStore() {
        return new InMemoryBusResponseProjectionStore();
    }

    @Bean
    BusConcurrencyGuard busConcurrencyGuard(BusConsumerProperties p) {
        BusConsumerProperties.Tuning tuning = p.getTuning();
        return new BusConcurrencyGuard(tuning.getPayloadMaxInFlight(), tuning.getBridgeMaxInFlight(),
                tuning.getProjectionMaxInFlight(), 64);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean(name = "runtimeRequestConsumer")
    AgentBusBrokerDeliveryPort agentBusBrokerDeliveryPort(
            @Qualifier("runtimeRequestConsumer") BrokerForwardingConsumerPort consumer,
            AgentBusBrokerProperties bus, Environment environment) {
        return new AgentBusBrokerDeliveryPort(consumer, consumerServiceId(environment),
                tenantId(bus), serviceId(environment));
    }

    @Bean
    @ConditionalOnBean(name = "runtimeResponseProducer")
    AgentBusResponsePublisher agentBusResponsePublisher(
            @Qualifier("runtimeResponseProducer") BrokerForwardingProducerPort producer, Environment environment) {
        return new AgentBusResponsePublisher(producer, serviceId(environment));
    }

    @Bean
    RequestHandlerBusA2aBridge busA2aRequestBridge(RequestHandler requestHandler) {
        return new RequestHandlerBusA2aBridge(requestHandler);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean({InMemoryBusResponseProjectionStore.class, AgentBusResponsePublisher.class})
    BusResponseRelay busResponseRelay(InMemoryBusResponseProjectionStore store, AgentBusResponsePublisher publisher,
            BusConsumerProperties p, BusConcurrencyGuard concurrency) {
        BusConsumerProperties.Tuning tuning = p.getTuning();
        return new BusResponseRelay(store, publisher::publish, BusExecutors.singleThreadScheduler("bus-response-relay"),
                tuning.getResponseRelayMaxAttempts(), tuning.getResponseRelayBackoff(), concurrency);
    }

    @Bean
    @ConditionalOnBean({InMemoryBusResponseProjectionStore.class, AgentBusResponsePublisher.class,
            BusResponseRelay.class})
    BusTaskProjectionCoordinator busTaskProjectionCoordinator(InMemoryBusResponseProjectionStore store,
            AgentBusResponsePublisher publisher, BusResponseRelay relay, BusConcurrencyGuard concurrency) {
        return new BusTaskProjectionCoordinator(store, publisher::publish, relay, concurrency);
    }

    @Bean
    @ConditionalOnBean({InMemoryBusResponseProjectionStore.class, BusResponseRelay.class})
    BusProjectionRepairer busProjectionRepairer(InMemoryBusResponseProjectionStore store, BusResponseRelay relay,
            AgentBusBrokerProperties bus) {
        return new BusProjectionRepairer(store, relay, tenantId(bus));
    }

    @Bean
    @ConditionalOnBean(BusProjectionRepairer.class)
    BusProjectionRepairScheduler busProjectionRepairScheduler(BusProjectionRepairer repairer, BusConsumerProperties p,
            org.springframework.beans.factory.ObjectProvider<TaskStoreProjectionPostProcessor> taskProjector,
            AgentBusBrokerProperties bus) {
        return new BusProjectionRepairScheduler(repairer, p.getTuning().getRepairInterval().toMillis(),
                taskProjector.getIfAvailable(), tenantId(bus));
    }

    @Bean
    @ConditionalOnBean({BusTaskProjectionCoordinator.class, StreamReferenceService.class})
    StreamReadyProjector streamReadyProjector(BusTaskProjectionCoordinator coordinator, StreamReferenceService refs) {
        return new StreamReadyProjector(coordinator, refs);
    }

    @Bean
    @ConditionalOnBean(BusTaskProjectionCoordinator.class)
    static TaskStoreProjectionPostProcessor taskStoreProjectionPostProcessor(ConfigurableListableBeanFactory beans) {
        return new TaskStoreProjectionPostProcessor(() -> beans.getBean(BusTaskProjectionCoordinator.class),
                () -> beans.getBean(InMemoryBusTaskAdmissionStore.class));
    }

    @Bean
    @ConditionalOnBean({RequestHandlerBusA2aBridge.class, AgentBusResponsePublisher.class})
    RuntimeBusEventConsumer runtimeBusEventConsumer(ConfigurableListableBeanFactory beans) {
        return new RuntimeBusEventConsumer(beans.getBean(BusEnvelopeValidator.class),
                event -> event.inlinePayload(), beans.getBean(InMemoryBusTaskAdmissionStore.class),
                beans.getBean(RequestHandlerBusA2aBridge.class), beans.getBean(BusTaskProjectionCoordinator.class),
                beans.getBeanProvider(StreamReadyProjector.class).getIfAvailable(),
                beans.getBeanProvider(TaskStoreProjectionPostProcessor.class).getIfAvailable(),
                beans.getBean(BusConcurrencyGuard.class));
    }

    @Bean
    @ConditionalOnBean({AgentBusBrokerDeliveryPort.class, RuntimeBusEventConsumer.class})
    BrokerDeliveryLoop brokerDeliveryLoop(AgentBusBrokerDeliveryPort broker, RuntimeBusEventConsumer consumer,
            AgentBusBrokerProperties bus, Environment environment) {
        return new BrokerDeliveryLoop(broker, d -> consumer.consume(d.envelope(), d.payload()),
                consumerServiceId(environment), tenantId(bus), Clock.systemUTC());
    }

    @Bean
    @ConditionalOnBean(BrokerDeliveryLoop.class)
    BrokerConsumerLifecycle brokerConsumerLifecycle(BrokerDeliveryLoop loop, BusConsumerProperties p) {
        return new BrokerConsumerLifecycle(loop, p.getTuning().getPollInterval().toMillis());
    }

    @Bean
    SmartInitializingSingleton busConsumerRequiredBeans(AgentBusBrokerProperties bus, Environment environment,
            ConfigurableListableBeanFactory beans) {
        return () -> {
            serviceId(environment);
            tenantId(bus);
            if (beans.getBeanProvider(RuntimeBusEventConsumer.class).getIfAvailable() == null) {
                throw new IllegalStateException("RuntimeBusEventConsumer bean is required");
            }
            if (beans.getBeanProvider(AgentBusBrokerDeliveryPort.class).getIfAvailable() == null) {
                throw new IllegalStateException("runtimeRequestConsumer/delivery adapter is required");
            }
            TaskStore configuredTaskStore = beans.getBeanProvider(TaskStore.class).getIfAvailable();
            if (configuredTaskStore == null) {
                throw new IllegalStateException("A2A TaskStore bean is required");
            }
            if (beans.getBeanProvider(AgentBusResponsePublisher.class).getIfAvailable() == null) {
                throw new IllegalStateException("runtimeResponseProducer/response publisher is required");
            }
        };
    }

    static String consumerServiceId(Environment environment) {
        return "runtime-" + serviceId(environment);
    }

    private static String tenantId(AgentBusBrokerProperties bus) {
        return require(bus.tenant(), "agent-bus.tenant");
    }

    private static String serviceId(Environment environment) {
        return require(environment.getProperty(SERVICE_ID_PROPERTY), SERVICE_ID_PROPERTY);
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when broker is configured");
        }
        return value;
    }
}
