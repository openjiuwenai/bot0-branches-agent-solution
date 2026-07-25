/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.InMemoryBroker;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.service.bus.consumer.RuntimeBusEventConsumer;
import com.openjiuwen.service.bus.consumer.a2a.ProjectingTaskStore;
import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.port.BusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.port.BusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusBrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusResponsePublisher;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.stream.StreamReferenceService;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Proxy;
import java.time.Duration;

/**
 * Tests BusConsumerAutoConfiguration behavior.
 *
 * @since 2026-07-22
 */
class BusConsumerAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BusConsumerAutoConfiguration.class))
            .withPropertyValues("openjiuwen.service.bus.consumer.enabled=true",
                    "openjiuwen.service.bus.consumer.allow-ephemeral-state=true",
                    "openjiuwen.service.bus.consumer.consumer-tenant-id=tenant-a",
                    "spring.application.name=runtime-a")
            .withBean(RequestHandler.class, BusConsumerAutoConfigurationTest::requestHandler)
            .withBean("runtimeResponseProducer", BrokerForwardingProducerPort.class,
                    () -> new InMemoryBroker(new DefaultBrokerTopicResolver(), "resp_in"))
            .withBean(AgentBusBrokerDeliveryPort.class, () -> {
                var broker = new InMemoryBroker(new DefaultBrokerTopicResolver(), "deliver");
                return new AgentBusBrokerDeliveryPort(broker.consumerFor("runtime-runtime-a"), "runtime-runtime-a",
                        "tenant-a", "runtime-a");
            }).withBean(TaskStore.class, InMemoryTaskStore::new);

    @Test
    void createsConsumerWhenEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RuntimeBusEventConsumer.class);
            assertThat(context).hasSingleBean(AgentBusResponsePublisher.class);
            assertThat(context).hasSingleBean(BusConsumerProperties.class);
            assertThat(context.getBean(TaskStore.class)).isInstanceOf(ProjectingTaskStore.class);
            assertThat(context).hasSingleBean(BusTaskAdmissionStore.class);
            assertThat(context).hasSingleBean(BusResponseProjectionStore.class);
            assertThat(context.getBean(BusTaskAdmissionStore.class))
                    .isInstanceOf(InMemoryBusTaskAdmissionStore.class);
            assertThat(context.getBean(BusResponseProjectionStore.class))
                    .isInstanceOf(InMemoryBusResponseProjectionStore.class);
        });
    }

    @Test
    void rejectsApplicationAdmissionStoreOverride() {
        contextRunner.withBean("applicationAdmissionStore", BusTaskAdmissionStore.class,
                InMemoryBusTaskAdmissionStore::new).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsApplicationProjectionStoreOverride() {
        contextRunner.withBean("applicationProjectionStore", BusResponseProjectionStore.class,
                InMemoryBusResponseProjectionStore::new).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void derivesConsumerServiceIdFromApplicationName() {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", "runtime-a");

        assertThat(BusConsumerAutoConfiguration.consumerServiceId(environment)).isEqualTo("runtime-runtime-a");
    }

    @Test
    void rejectsMissingApplicationName() {
        assertThatThrownBy(() -> BusConsumerAutoConfiguration.consumerServiceId(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.application.name");
    }

    @Test
    void bindsOptionalTuningDurations() {
        contextRunner.withPropertyValues("openjiuwen.service.bus.consumer.tuning.poll-interval=2s",
                "openjiuwen.service.bus.consumer.tuning.response-relay-backoff=250ms",
                "openjiuwen.service.bus.consumer.tuning.repair-interval=30s").run(context -> {
                    BusConsumerProperties.Tuning tuning = context.getBean(BusConsumerProperties.class).getTuning();
                    assertThat(tuning.getPollInterval()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(tuning.getResponseRelayBackoff()).isEqualTo(Duration.ofMillis(250));
                    assertThat(tuning.getRepairInterval()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void usesConfiguredBridge() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(RequestHandlerBusA2aBridge.class));
    }

    @Test
    void createsStreamReferenceServiceWhenSecretConfigured() {
        contextRunner.withPropertyValues("openjiuwen.service.bus.consumer.stream-ref-secret=test-secret")
                .run(context -> assertThat(context).hasSingleBean(StreamReferenceService.class));
    }

    @Test
    void disabledDoesNotRequireRuntimeBusDependencies() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BusConsumerAutoConfiguration.class))
                .withPropertyValues("openjiuwen.service.bus.consumer.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(RuntimeBusEventConsumer.class));
    }

    @Test
    void enabledFailsFastWhenRequiredRuntimeBeansAreMissing() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BusConsumerAutoConfiguration.class))
                .withPropertyValues("openjiuwen.service.bus.consumer.enabled=true",
                        "openjiuwen.service.bus.consumer.allow-ephemeral-state=true",
                        "openjiuwen.service.bus.consumer.consumer-tenant-id=tenant-a",
                        "spring.application.name=runtime-a")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("RequestHandler");
                });
    }

    private static RequestHandler requestHandler() {
        return (RequestHandler) Proxy.newProxyInstance(RequestHandler.class.getClassLoader(),
                new Class<?>[]{RequestHandler.class},
                (proxy, method, arguments) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
