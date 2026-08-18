/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.autoconfigure;

import com.openjiuwen.bus.forwarding.common.AgentBusBrokerProperties;
import com.openjiuwen.bus.forwarding.spi.AgentBusRequestSubmitter;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.bus.consumer.BusTaskProjectionCoordinator;
import com.openjiuwen.service.bus.consumer.RuntimeBusEventConsumer;
import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.a2a.TaskStoreProjectionPostProcessor;
import com.openjiuwen.service.bus.consumer.caller.AgentBusCallerResponseLifecycle;
import com.openjiuwen.service.bus.consumer.caller.AgentBusRemoteAgentCaller;
import com.openjiuwen.service.bus.consumer.caller.A2ATaskSubscriptionClient;
import com.openjiuwen.service.bus.consumer.caller.RuntimeRdcClient;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-configures the FEAT-017 bus consumer and its A2A bridge.
 *
 * @since 2026-07-22
 */
@AutoConfiguration
@AutoConfigureAfter(name = {
        "com.openjiuwen.bus.forwarding.common.AgentBusReliabilityAutoConfiguration",
        "com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.AgentBusRuntimeRoleAutoConfiguration",
        "com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.AgentBusCallerRoleAutoConfiguration"
})
@AutoConfigureBefore(name = "com.openjiuwen.service.app.autoconfigure.A2AAutoConfiguration")
@EnableConfigurationProperties({BusConsumerProperties.class, AgentBusBrokerProperties.class})
@EnableAspectJAutoProxy
@ConditionalOnProperty(prefix = "openjiuwen.service.bus.consumer", name = "enabled", havingValue = "true")
public class BusConsumerAutoConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(BusConsumerAutoConfiguration.class);
    private static final String SERVICE_ID_PROPERTY = "openjiuwen.service.service-id";
    private static final long STREAM_REF_TTL_SECONDS = 60L * 60L;
    private static final int CALLER_IO_THREADS = 4;

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
    @ConditionalOnBean(value = AgentBusRequestSubmitter.class, name = "responseConsumer")
    RuntimeRdcClient runtimeRdcClient(BusConsumerProperties properties) {
        return new RuntimeRdcClient(properties.getRegistryBaseUrl(), properties.getLocalCacheTtl());
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService agentBusCallerExecutor() {
        return executor(CALLER_IO_THREADS, "bus-caller-io");
    }

    @Bean
    CallerSettings agentBusCallerSettings(AgentBusBrokerProperties bus, Environment environment) {
        return new CallerSettings(tenantId(bus), serviceId(environment), bus.responseTimeoutMs());
    }

    @Bean
    A2ATaskSubscriptionClient a2aTaskSubscriptionClient() {
        return new A2ATaskSubscriptionClient();
    }

    @Bean
    AgentBusRemoteAgentCaller agentBusRemoteAgentCaller(ObjectProvider<RuntimeRdcClient> registry,
            ObjectProvider<AgentBusRequestSubmitter> requestSubmitter,
            @Qualifier("agentBusCallerExecutor") ExecutorService agentBusCallerExecutor,
            CallerSettings settings,
            ObjectProvider<A2ATaskSubscriptionClient> subscriptionClient) {
        RuntimeRdcClient registryClient = registry.getIfAvailable();
        AgentBusRequestSubmitter submitter = requestSubmitter.getIfAvailable();
        if (registryClient == null || submitter == null) {
            throw new IllegalStateException("Agent Bus caller role requires AgentBusRequestSubmitter "
                    + "and responseConsumer when bus consumer is enabled");
        }
        return new AgentBusRemoteAgentCaller(registryClient, submitter, agentBusCallerExecutor,
                settings.tenantId(), settings.sourceServiceId(), settings.responseTimeoutMillis(),
                subscriptionClient.getIfAvailable(A2ATaskSubscriptionClient::new));
    }

    @Bean
    @ConditionalOnBean(value = AgentBusRequestSubmitter.class, name = "responseConsumer")
    AgentBusCallerResponseLifecycle agentBusCallerResponseLifecycle(
            @Qualifier("responseConsumer") BrokerForwardingConsumerPort consumer,
            AgentBusRemoteAgentCaller caller, AgentBusBrokerProperties bus, Environment environment) {
        ExecutorService executor = executor(1, "bus-caller-response");
        String serviceId = serviceId(environment);
        return new AgentBusCallerResponseLifecycle(consumer, caller, executor,
                callerConsumerServiceId(serviceId), tenantId(bus), serviceId);
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
    RequestHandlerBusA2aBridge busA2aRequestBridge(ObjectProvider<RequestHandler> requestHandler) {
        return new RequestHandlerBusA2aBridge(requestHandler::getIfAvailable);
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
            Map<String, RemoteAgentCaller> callers = beans.getBeansOfType(RemoteAgentCaller.class);
            if (callers.size() != 1 || !callers.containsKey("agentBusRemoteAgentCaller")) {
                throw new IllegalStateException("Agent Bus must be the only RemoteAgentCaller when bus is enabled");
            }
            requireNamedBean(beans, "runtimeRequestConsumer",
                    "runtimeRequestConsumer is required when bus consumer is enabled");
            requireNamedBean(beans, "runtimeResponseProducer",
                    "runtimeResponseProducer is required when bus consumer is enabled");
            requireNamedBean(beans, "responseConsumer",
                    "responseConsumer is required when bus consumer is enabled");
            requireBean(beans, AgentBusRequestSubmitter.class,
                    "AgentBusRequestSubmitter is required when bus consumer is enabled");
            requireBean(beans, RequestHandler.class, "A2A RequestHandler is required for the runtime role");
            requireBean(beans, TaskStore.class, "A2A TaskStore is required for the runtime role");
            requireBean(beans, AgentBusBrokerDeliveryPort.class,
                    "AgentBusBrokerDeliveryPort could not be assembled for the runtime role");
            requireBean(beans, AgentBusResponsePublisher.class,
                    "AgentBusResponsePublisher could not be assembled for the runtime role");
            requireBean(beans, RuntimeBusEventConsumer.class,
                    "RuntimeBusEventConsumer could not be assembled for the runtime role");
            requireBean(beans, AgentBusCallerResponseLifecycle.class,
                    "AgentBusCallerResponseLifecycle could not be assembled for the caller role");
        };
    }

    private static void requireNamedBean(ConfigurableListableBeanFactory beans, String name, String message) {
        if (!beans.containsBean(name)) {
            throw new IllegalStateException(message);
        }
    }

    private static <T> void requireBean(ConfigurableListableBeanFactory beans, Class<T> type, String message) {
        if (beans.getBeanProvider(type).getIfAvailable() == null) {
            throw new IllegalStateException(message);
        }
    }

    static String consumerServiceId(Environment environment) {
        return "runtime-" + serviceId(environment);
    }

    static String callerConsumerServiceId(String serviceId) {
        return "runtime-caller-" + require(serviceId, "serviceId");
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

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger index = new AtomicInteger();
        ThreadFactory delegate = Executors.defaultThreadFactory();
        return runnable -> {
            Thread thread = delegate.newThread(runnable);
            thread.setName(prefix + "-" + index.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((worker, failure) ->
                    LOG.error("Uncaught failure in thread {}", worker.getName(), failure));
            return thread;
        };
    }

    private static ExecutorService executor(int threads, String threadNamePrefix) {
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), daemonThreadFactory(threadNamePrefix));
    }

    record CallerSettings(String tenantId, String sourceServiceId, long responseTimeoutMillis) {
    }
}
