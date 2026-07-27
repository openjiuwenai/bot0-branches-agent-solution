/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.common.AgentBusBrokerProperties;
import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.InMemoryBroker;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingProducerPort;
import com.openjiuwen.service.bus.consumer.RuntimeBusEventConsumer;
import com.openjiuwen.service.bus.consumer.a2a.ProjectingTaskStore;
import com.openjiuwen.service.bus.consumer.a2a.RequestHandlerBusA2aBridge;
import com.openjiuwen.service.bus.consumer.model.Admission;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusBrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusResponsePublisher;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusResponseProjectionStore;
import com.openjiuwen.service.bus.consumer.store.InMemoryBusTaskAdmissionStore;
import com.openjiuwen.service.bus.consumer.stream.StreamReadyProjector;
import com.openjiuwen.service.bus.consumer.stream.StreamReferenceService;
import com.openjiuwen.service.bus.consumer.stream.StreamReferenceSubscriptionAspect;

import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
                    "agent-bus.tenant=tenant-a",
                    "openjiuwen.service.service-id=runtime-a")
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
            assertThat(context).hasSingleBean(InMemoryBusTaskAdmissionStore.class);
            assertThat(context).hasSingleBean(InMemoryBusResponseProjectionStore.class);
            assertThat(context).hasSingleBean(StreamReferenceService.class);
            assertThat(context).hasSingleBean(StreamReferenceSubscriptionAspect.class);
            assertThat(context.getBean(AgentBusBrokerProperties.class).tenant()).isEqualTo("tenant-a");
        });
    }

    @Test
    void derivesConsumerServiceIdFromConfiguredServiceId() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("openjiuwen.service.service-id", "runtime-a");

        assertThat(BusConsumerAutoConfiguration.consumerServiceId(environment)).isEqualTo("runtime-runtime-a");
    }

    @Test
    void rejectsMissingServiceIdEvenWhenApplicationNameIsPresent() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.application.name", "runtime-a");

        assertThatThrownBy(() -> BusConsumerAutoConfiguration.consumerServiceId(environment))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openjiuwen.service.service-id");
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
    void createsStreamSupportWithoutUserSecret() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(StreamReferenceService.class);
            assertThat(context).hasSingleBean(StreamReadyProjector.class);
            StreamReferenceService references = context.getBean(StreamReferenceService.class);
            String streamRef = references.issue("tenant-a", "task-1", 100);
            assertThat(references.validate(streamRef, "tenant-a", "task-1", 200)
                    .expiresAtEpochSeconds()).isEqualTo(3700);
        });
    }

    @Test
    void interceptsTaskSubscriptionBeforeRequestHandler() {
        contextRunner.run(context -> {
            StreamReferenceService references = context.getBean(StreamReferenceService.class);
            InMemoryBusTaskAdmissionStore admissions = context.getBean(InMemoryBusTaskAdmissionStore.class);
            admissions.reserve(new Admission("tenant-a", "idem-1", "digest", "reserved-task", "CLIENT", "corr",
                    "trace", "gateway", "runtime-a", null, Admission.State.RESERVED));
            admissions.markAdmitted("tenant-a", "idem-1", "task-1");
            String streamReference = references.issue("tenant-a", "task-1",
                    System.currentTimeMillis() / 1000);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-OpenJiuwen-Stream-Ref", streamReference);
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try {
                assertThatThrownBy(() -> context.getBean(RequestHandler.class)
                        .onSubscribeToTask(new TaskIdParams("task-2", "tenant-a"), null))
                        .isInstanceOf(TaskNotFoundError.class);
                assertThat(context.getBean(RequestHandler.class)
                        .onSubscribeToTask(new TaskIdParams("task-1", "tenant-a"), null)).isNull();
                assertThat(context.getBean(RequestHandler.class)
                        .onSubscribeToTask(new TaskIdParams("task-1"), null)).isNull();
                RequestContextHolder.setRequestAttributes(
                        new ServletRequestAttributes(new MockHttpServletRequest()));
                assertThatThrownBy(() -> context.getBean(RequestHandler.class)
                        .onSubscribeToTask(new TaskIdParams("task-1", "tenant-a"), null))
                        .isInstanceOf(TaskNotFoundError.class);
                assertThatThrownBy(() -> context.getBean(RequestHandler.class)
                        .onSubscribeToTask(new TaskIdParams("task-1", "tenant-other"), null))
                        .isInstanceOf(TaskNotFoundError.class);
                assertThat(context.getBean(RequestHandler.class)
                        .onSubscribeToTask(new TaskIdParams("standard-task", "tenant-a"), null)).isNull();
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
            assertThatThrownBy(() -> context.getBean(RequestHandler.class)
                    .onSubscribeToTask(new TaskIdParams("task-1", "tenant-a"), null))
                    .isInstanceOf(TaskNotFoundError.class);
            assertThat(context.getBean(RequestHandler.class)
                    .onSubscribeToTask(new TaskIdParams("standard-task", "tenant-a"), null)).isNull();
        });
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
                        "agent-bus.tenant=tenant-a",
                        "openjiuwen.service.service-id=runtime-a")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("RequestHandler");
                });
    }

    private static RequestHandler requestHandler() {
        return (RequestHandler) Proxy.newProxyInstance(RequestHandler.class.getClassLoader(),
                new Class<?>[]{RequestHandler.class},
                (proxy, method, arguments) -> {
                    if ("onSubscribeToTask".equals(method.getName())) {
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
