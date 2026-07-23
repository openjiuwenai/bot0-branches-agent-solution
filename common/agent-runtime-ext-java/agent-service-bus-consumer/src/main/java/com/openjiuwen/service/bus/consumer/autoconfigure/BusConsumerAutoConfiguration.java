/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.autoconfigure;

import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration;
import com.openjiuwen.service.bus.consumer.BusTaskProjectionCoordinator;
import com.openjiuwen.service.bus.consumer.RuntimeBusEventConsumer;
import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.a2a.TaskStoreProjectionPostProcessor;
import com.openjiuwen.service.bus.consumer.observability.BusConsumerTelemetry;
import com.openjiuwen.service.bus.consumer.port.BrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.port.BusA2aRequestBridge;
import com.openjiuwen.service.bus.consumer.port.BusPayloadResolver;
import com.openjiuwen.service.bus.consumer.port.BusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.port.BusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.port.BusTaskStateProjector;
import com.openjiuwen.service.bus.consumer.port.RuntimeBusResponsePublisher;
import com.openjiuwen.service.bus.consumer.port.RuntimeBusTargetIdentity;
import com.openjiuwen.service.bus.consumer.relay.BusProjectionRepairScheduler;
import com.openjiuwen.service.bus.consumer.relay.BusProjectionRepairer;
import com.openjiuwen.service.bus.consumer.relay.BusResponseRelay;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusBrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusOutboxResponsePublisher;
import com.openjiuwen.service.bus.consumer.runtime.BrokerConsumerLifecycle;
import com.openjiuwen.service.bus.consumer.runtime.BrokerDeliveryLoop;
import com.openjiuwen.service.bus.consumer.runtime.BusConcurrencyGuard;
import com.openjiuwen.service.bus.consumer.runtime.BusExecutors;
import com.openjiuwen.service.bus.consumer.runtime.DurabilityGuard;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.stream.StreamReadyProjector;
import com.openjiuwen.service.bus.consumer.stream.StreamReferenceService;
import com.openjiuwen.service.bus.consumer.validation.BusEnvelopeValidator;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * Auto-configures the FEAT-017 bus consumer and its A2A bridge.
 *
 * @since 2026-07-22
 */
@AutoConfiguration
@AutoConfigureBefore(A2AAutoConfiguration.class)
@EnableConfigurationProperties(BusConsumerProperties.class)
@ConditionalOnProperty(prefix = "openjiuwen.service.bus.consumer", name = "enabled", havingValue = "true")
public class BusConsumerAutoConfiguration {
    private static final long STREAM_REF_TTL_SECONDS = 300L;

    @Bean
    @ConditionalOnMissingBean
    Clock busConsumerClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    BusEnvelopeValidator busEnvelopeValidator(RuntimeBusTargetIdentity identity, Clock clock, BusConsumerProperties p) {
        return new BusEnvelopeValidator(clock, identity.serviceId());
    }

    @Bean
    @ConditionalOnMissingBean
    RuntimeBusTargetIdentity runtimeBusTargetIdentity(BusConsumerProperties p) {
        return () -> p.getTargetServiceId();
    }

    @Bean
    @ConditionalOnProperty(prefix = "openjiuwen.service.bus.consumer", name = "stream-ref-secret")
    StreamReferenceService streamReferenceService(BusConsumerProperties p) {
        return new StreamReferenceService(p.getStreamRefSecret(), STREAM_REF_TTL_SECONDS);
    }

    @Bean
    @ConditionalOnMissingBean
    BusTaskAdmissionStore busTaskAdmissionStore() {
        return new InMemoryBusTaskAdmissionStore();
    }

    @Bean
    @ConditionalOnMissingBean
    BusResponseProjectionStore busResponseProjectionStore() {
        return new InMemoryBusResponseProjectionStore();
    }

    @Bean
    @ConditionalOnMissingBean(name = "busDurabilityGuard")
    Object busDurabilityGuard(BusConsumerProperties p, BusTaskAdmissionStore admission,
            BusResponseProjectionStore projection) {
        DurabilityGuard.verify(true, p.isAllowEphemeralState(), admission, projection);
        return new Object();
    }

    @Bean
    @ConditionalOnMissingBean
    BusPayloadResolver busPayloadResolver() {
        return e -> e.inlinePayload();
    }

    @Bean
    @ConditionalOnMissingBean
    BusConsumerTelemetry busConsumerTelemetry(ObjectProvider<MeterRegistry> meters,
            ObjectProvider<ObservationRegistry> observations) {
        return new BusConsumerTelemetry(meters.getIfAvailable(), observations.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    BusConcurrencyGuard busConcurrencyGuard(BusConsumerProperties p) {
        BusConsumerProperties.Tuning tuning = p.getTuning();
        return new BusConcurrencyGuard(tuning.getPayloadMaxInFlight(), tuning.getBridgeMaxInFlight(),
                tuning.getProjectionMaxInFlight(), 64);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(BrokerDeliveryPort.class)
    @ConditionalOnBean(name = "runtimeRequestConsumer")
    BrokerDeliveryPort agentBusBrokerDeliveryPort(
            @Qualifier("runtimeRequestConsumer") BrokerForwardingConsumerPort consumer, BusConsumerProperties p,
            RuntimeBusTargetIdentity identity) {
        return new AgentBusBrokerDeliveryPort(consumer, consumerServiceId(identity),
                require(p.getConsumerTenantId(), "consumer-tenant-id"),
                require(identity.serviceId(), "target-service-id"));
    }

    @Bean
    @ConditionalOnMissingBean(RuntimeBusResponsePublisher.class)
    @ConditionalOnBean(ForwardingOutboxPort.class)
    RuntimeBusResponsePublisher agentBusOutboxResponsePublisher(ForwardingOutboxPort outbox,
            RuntimeBusTargetIdentity identity) {
        return new AgentBusOutboxResponsePublisher(outbox, require(identity.serviceId(), "target-service-id"));
    }

    @Bean
    @ConditionalOnMissingBean
    BusA2aRequestBridge busA2aRequestBridge(RequestHandler requestHandler) {
        return new RequestHandlerBusA2aBridge(requestHandler);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnBean({BusResponseProjectionStore.class, RuntimeBusResponsePublisher.class})
    BusResponseRelay busResponseRelay(BusResponseProjectionStore store, RuntimeBusResponsePublisher publisher,
            BusConsumerProperties p, BusConsumerTelemetry telemetry, BusConcurrencyGuard concurrency) {
        BusConsumerProperties.Tuning tuning = p.getTuning();
        return new BusResponseRelay(store, publisher, BusExecutors.singleThreadScheduler("bus-response-relay"),
                tuning.getResponseRelayMaxAttempts(), tuning.getResponseRelayBackoff(), telemetry, concurrency);
    }

    @Bean
    @ConditionalOnBean({BusResponseProjectionStore.class, RuntimeBusResponsePublisher.class, BusResponseRelay.class})
    BusTaskProjectionCoordinator busTaskProjectionCoordinator(BusResponseProjectionStore store,
            RuntimeBusResponsePublisher publisher, BusResponseRelay relay, BusConsumerTelemetry telemetry,
            BusConcurrencyGuard concurrency) {
        return new BusTaskProjectionCoordinator(store, publisher, relay, telemetry, concurrency);
    }

    @Bean
    @ConditionalOnBean({BusResponseProjectionStore.class, BusResponseRelay.class})
    BusProjectionRepairer busProjectionRepairer(BusResponseProjectionStore store, BusResponseRelay relay,
            BusConsumerProperties p, BusConsumerTelemetry telemetry) {
        return new BusProjectionRepairer(store, relay, require(p.getConsumerTenantId(), "consumer-tenant-id"),
                telemetry);
    }

    @Bean
    @ConditionalOnBean(BusProjectionRepairer.class)
    BusProjectionRepairScheduler busProjectionRepairScheduler(BusProjectionRepairer repairer, BusConsumerProperties p,
            org.springframework.beans.factory.ObjectProvider<BusTaskStateProjector> taskProjector) {
        return new BusProjectionRepairScheduler(repairer, p.getTuning().getRepairInterval().toMillis(),
                taskProjector.getIfAvailable(), require(p.getConsumerTenantId(), "consumer-tenant-id"));
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
                () -> beans.getBean(BusTaskAdmissionStore.class));
    }

    @Bean
    @ConditionalOnBean({BusA2aRequestBridge.class, RuntimeBusResponsePublisher.class})
    RuntimeBusEventConsumer runtimeBusEventConsumer(ConfigurableListableBeanFactory beans) {
        return new RuntimeBusEventConsumer(beans.getBean(BusEnvelopeValidator.class),
                beans.getBean(BusPayloadResolver.class), beans.getBean(BusTaskAdmissionStore.class),
                beans.getBean(BusA2aRequestBridge.class), beans.getBean(BusTaskProjectionCoordinator.class),
                beans.getBeanProvider(StreamReadyProjector.class).getIfAvailable(),
                beans.getBeanProvider(BusTaskStateProjector.class).getIfAvailable(),
                beans.getBean(BusConsumerTelemetry.class), beans.getBean(BusConcurrencyGuard.class));
    }

    @Bean
    @ConditionalOnBean({BrokerDeliveryPort.class, RuntimeBusEventConsumer.class})
    BrokerDeliveryLoop brokerDeliveryLoop(BrokerDeliveryPort broker, RuntimeBusEventConsumer consumer,
            BusConsumerProperties p, RuntimeBusTargetIdentity identity, Clock clock) {
        return new BrokerDeliveryLoop(broker, d -> consumer.consume(d.envelope(), d.payload()),
                consumerServiceId(identity),
                require(p.getConsumerTenantId(), "consumer-tenant-id"), clock);
    }

    @Bean
    @ConditionalOnBean(BrokerDeliveryLoop.class)
    BrokerConsumerLifecycle brokerConsumerLifecycle(BrokerDeliveryLoop loop, BusConsumerProperties p) {
        return new BrokerConsumerLifecycle(loop, p.getTuning().getPollInterval().toMillis());
    }

    @Bean
    SmartInitializingSingleton busConsumerRequiredBeans(BusConsumerProperties p, RuntimeBusTargetIdentity identity,
            ConfigurableListableBeanFactory beans) {
        return () -> {
            require(identity.serviceId(), "target-service-id");
            require(p.getConsumerTenantId(), "consumer-tenant-id");
            if (beans.getBeanProvider(RuntimeBusEventConsumer.class).getIfAvailable() == null) {
                throw new IllegalStateException("RuntimeBusEventConsumer bean is required");
            }
            if (beans.getBeanProvider(BrokerDeliveryPort.class).getIfAvailable() == null) {
                throw new IllegalStateException("runtimeRequestConsumer/delivery adapter is required");
            }
            TaskStore configuredTaskStore = beans.getBeanProvider(TaskStore.class).getIfAvailable();
            if (configuredTaskStore == null) {
                throw new IllegalStateException("A2A TaskStore bean is required");
            }
            DurabilityGuard.verifyTaskStore(p.isAllowEphemeralState(), configuredTaskStore);
            if (beans.getBeanProvider(RuntimeBusResponsePublisher.class).getIfAvailable() == null) {
                throw new IllegalStateException("RuntimeBusResponsePublisher bean is required");
            }
        };
    }

    static String consumerServiceId(RuntimeBusTargetIdentity identity) {
        return "runtime-" + require(identity.serviceId(), "target-service-id");
    }

    private static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when broker is configured");
        }
        return value;
    }
}
