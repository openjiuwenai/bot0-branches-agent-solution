/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.bus.consumer.RuntimeBusEventConsumer;
import com.openjiuwen.service.bus.consumer.a2a.ProjectingTaskStore;
import com.openjiuwen.service.bus.consumer.model.BusDispatchResult;
import com.openjiuwen.service.bus.consumer.port.BrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.port.BusA2aRequestBridge;
import com.openjiuwen.service.bus.consumer.port.RuntimeBusResponsePublisher;
import com.openjiuwen.service.bus.consumer.port.RuntimeBusTargetIdentity;
import com.openjiuwen.service.bus.consumer.stream.StreamReferenceService;

import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
                    "openjiuwen.service.bus.consumer.target-service-id=runtime-a",
                    "openjiuwen.service.bus.consumer.consumer-tenant-id=tenant-a")
            .withBean(BusA2aRequestBridge.class,
                    () -> (event, payload) -> new BusDispatchResult("task", null, null, false))
            .withBean(RuntimeBusResponsePublisher.class, () -> projection -> {
            }).withBean(BrokerDeliveryPort.class, () -> new BrokerDeliveryPort() {
                /** {@inheritDoc} */
                @Override
                public java.util.Optional<Delivery> poll(String consumer, String tenant, long now) {
                    return java.util.Optional.empty();
                }

                /** {@inheritDoc} */
                @Override
                public void commit(Delivery delivery) {
                }

                /** {@inheritDoc} */
                @Override
                public void reject(Delivery delivery, RejectReason reason) {
                }
            }).withBean(TaskStore.class, InMemoryTaskStore::new);

    @Test
    void createsConsumerWhenEnabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RuntimeBusEventConsumer.class);
            assertThat(context).hasSingleBean(BusConsumerProperties.class);
            assertThat(context.getBean(TaskStore.class)).isInstanceOf(ProjectingTaskStore.class);
        });
    }

    @Test
    void derivesConsumerServiceIdFromRuntimeTargetIdentity() {
        RuntimeBusTargetIdentity identity = () -> "runtime-a";

        assertThat(BusConsumerAutoConfiguration.consumerServiceId(identity)).isEqualTo("runtime-runtime-a");
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
    void customBridgeReplacesDefault() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(BusA2aRequestBridge.class));
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
                        "openjiuwen.service.bus.consumer.target-service-id=runtime-a",
                        "openjiuwen.service.bus.consumer.consumer-tenant-id=tenant-a")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("RequestHandler");
                });
    }
}
