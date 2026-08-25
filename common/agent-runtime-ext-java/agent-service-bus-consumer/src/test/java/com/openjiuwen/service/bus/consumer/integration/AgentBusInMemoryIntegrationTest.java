/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.InMemoryBroker;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.BrokerOutboundMessage;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusBrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.BrokerDeliveryLoop;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Integrates the FEAT-017 runtime consumer with the canonical agent-bus in-memory broker testkit.
 *
 * @since 2026-07-22
 */
class AgentBusInMemoryIntegrationTest {
    @Test
    void consumesGatewayDeliveryAndReturnsAcceptedAndResponseToBus() {
        InMemoryBroker requestBus = new InMemoryBroker(new DefaultBrokerTopicResolver(), "deliver");
        var runtimeBroker = requestBus.consumerFor(Feat017BusIntegrationSupport.RUNTIME_CONSUMER);
        var deliveryPort = new AgentBusBrokerDeliveryPort(runtimeBroker,
                Feat017BusIntegrationSupport.RUNTIME_CONSUMER, Feat017BusIntegrationSupport.TENANT,
                Feat017BusIntegrationSupport.RUNTIME);
        var responseProducer = new Feat017BusIntegrationSupport.RecordingProducer();
        AtomicInteger bridgeCalls = new AtomicInteger();
        var runtime = Feat017BusIntegrationSupport.runtime(responseProducer, bridgeCalls);
        var loop = new BrokerDeliveryLoop(deliveryPort,
                delivery -> runtime.consume(delivery.envelope(), delivery.payload()),
                Feat017BusIntegrationSupport.RUNTIME_CONSUMER, Feat017BusIntegrationSupport.TENANT,
                Clock.fixed(Instant.ofEpochMilli(Feat017BusIntegrationSupport.NOW), ZoneOffset.UTC));

        BrokerProduceOutcome requestOutcome = requestBus.produce(Feat017BusIntegrationSupport.requestRecord(
                "request-1", "correlation-1", "idempotency-1"), Feat017BusIntegrationSupport.NOW);
        assertThat(requestOutcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ACCEPTED);
        assertThat(loop.tick()).isTrue();
        assertThat(loop.tick()).isFalse();
        assertThat(bridgeCalls).hasValue(1);

        List<BrokerOutboundMessage> responses = responseProducer.messages();
        assertThat(responses).extracting(message -> message.headers().eventType()).containsExactly(
                AgentBusEventType.INVOCATION_ACCEPTED, AgentBusEventType.INVOCATION_RESPONSE);
        assertThat(responses).allSatisfy(message -> {
            assertThat(message.headers().sourceServiceId()).isEqualTo(Feat017BusIntegrationSupport.RUNTIME);
            assertThat(message.headers().targetServiceId()).isEqualTo(Feat017BusIntegrationSupport.GATEWAY);
            assertThat(message.headers().correlationId()).isEqualTo("correlation-1");
        });

        List<BrokerInboundMessage> gatewayMessages = publishResponsesToGateway(responses);
        assertThat(gatewayMessages).extracting(BrokerInboundMessage::eventType).containsExactly(
                AgentBusEventType.INVOCATION_ACCEPTED, AgentBusEventType.INVOCATION_RESPONSE);
        assertThat(gatewayMessages).allSatisfy(message -> {
            assertThat(message.targetServiceId()).isEqualTo(Feat017BusIntegrationSupport.GATEWAY);
            assertThat(message.payloadRef()).isNull();
            assertThat(message.inlinePayload()).contains("\"taskId\":");
        });
    }

    @Test
    void sameIdempotencyKeyDoesNotInvokeA2aBridgeTwice() {
        InMemoryBroker requestBus = new InMemoryBroker(new DefaultBrokerTopicResolver(), "deliver");
        var runtimeBroker = requestBus.consumerFor(Feat017BusIntegrationSupport.RUNTIME_CONSUMER);
        var deliveryPort = new AgentBusBrokerDeliveryPort(runtimeBroker,
                Feat017BusIntegrationSupport.RUNTIME_CONSUMER, Feat017BusIntegrationSupport.TENANT,
                Feat017BusIntegrationSupport.RUNTIME);
        var responseProducer = new Feat017BusIntegrationSupport.RecordingProducer();
        AtomicInteger bridgeCalls = new AtomicInteger();
        var runtime = Feat017BusIntegrationSupport.runtime(responseProducer, bridgeCalls);
        var loop = new BrokerDeliveryLoop(deliveryPort,
                delivery -> runtime.consume(delivery.envelope(), delivery.payload()),
                Feat017BusIntegrationSupport.RUNTIME_CONSUMER, Feat017BusIntegrationSupport.TENANT,
                Clock.fixed(Instant.ofEpochMilli(Feat017BusIntegrationSupport.NOW), ZoneOffset.UTC));

        requestBus.produce(Feat017BusIntegrationSupport.requestRecord("request-1", "correlation-1", "same-key"),
                Feat017BusIntegrationSupport.NOW);
        assertThat(loop.tick()).isTrue();
        requestBus.produce(Feat017BusIntegrationSupport.requestRecord("request-2", "correlation-1", "same-key"),
                Feat017BusIntegrationSupport.NOW + 1);
        assertThat(loop.tick()).isTrue();

        assertThat(bridgeCalls).hasValue(1);
        assertThat(responseProducer.messages()).extracting(message -> message.headers().eventType())
                .containsExactly(AgentBusEventType.INVOCATION_ACCEPTED, AgentBusEventType.INVOCATION_RESPONSE,
                        AgentBusEventType.INVOCATION_ACCEPTED);
    }

    @Test
    void queryUsesRuntimeTaskIdNotClientInvocationId() {
        InMemoryBroker requestBus = new InMemoryBroker(new DefaultBrokerTopicResolver(), "deliver");
        var runtimeBroker = requestBus.consumerFor(Feat017BusIntegrationSupport.RUNTIME_CONSUMER);
        var deliveryPort = new AgentBusBrokerDeliveryPort(runtimeBroker,
                Feat017BusIntegrationSupport.RUNTIME_CONSUMER, Feat017BusIntegrationSupport.TENANT,
                Feat017BusIntegrationSupport.RUNTIME);
        String taskId = "runtime-task-query";
        String clientInvocationId = "client-invocation-decoy";
        var taskStore = new Feat017BusIntegrationSupport.RecordingTaskStore();
        taskStore.save(Feat017BusIntegrationSupport.completedTask(taskId, "query-context"), true);
        taskStore.save(Feat017BusIntegrationSupport.completedTask(clientInvocationId, "decoy-context"), true);
        var responseProducer = new Feat017BusIntegrationSupport.RecordingProducer();
        var runtime = Feat017BusIntegrationSupport.queryRuntime(responseProducer, taskStore,
                Feat017BusIntegrationSupport.queryPayload(taskId, clientInvocationId));
        var loop = new BrokerDeliveryLoop(deliveryPort,
                delivery -> runtime.consume(delivery.envelope(), delivery.payload()),
                Feat017BusIntegrationSupport.RUNTIME_CONSUMER, Feat017BusIntegrationSupport.TENANT,
                Clock.fixed(Instant.ofEpochMilli(Feat017BusIntegrationSupport.NOW), ZoneOffset.UTC));

        BrokerProduceOutcome requestOutcome = requestBus.produce(Feat017BusIntegrationSupport.requestRecord(
                AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED, "query-request", "query-correlation",
                "query-idempotency"), Feat017BusIntegrationSupport.NOW);
        assertThat(requestOutcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ACCEPTED);
        assertThat(loop.tick()).isTrue();
        assertThat(taskStore.requestedIds()).containsExactly(taskId);

        List<BrokerOutboundMessage> responses = responseProducer.messages();
        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.headers().eventType()).isEqualTo(AgentBusEventType.INVOCATION_RESPONSE);
            assertThat(response.headers().payloadRef()).isNull();
            assertThat(response.headers().inlinePayload()).contains("\"taskId\":\"" + taskId + "\"")
                    .doesNotContain(clientInvocationId);
        });
        List<BrokerInboundMessage> gatewayMessages = publishResponsesToGateway(responses);
        assertThat(gatewayMessages).singleElement().satisfies(message -> {
            assertThat(message.eventType()).isEqualTo(AgentBusEventType.INVOCATION_RESPONSE);
            assertThat(message.payloadRef()).isNull();
            assertThat(message.inlinePayload()).contains("\"taskId\":\"" + taskId + "\"")
                    .doesNotContain(clientInvocationId);
        });
    }

    private static List<BrokerInboundMessage> publishResponsesToGateway(List<BrokerOutboundMessage> responses) {
        InMemoryBroker responseBus = new InMemoryBroker(new DefaultBrokerTopicResolver(), "resp_in");
        var gateway = responseBus.consumerFor("gateway-feat017-response");
        DeliveryFilter filter = DeliveryFilter.forRuntime(Feat017BusIntegrationSupport.TENANT,
                Feat017BusIntegrationSupport.GATEWAY);
        gateway.subscribe("gateway-feat017-response", AgentBusEventType.INVOCATION_ACCEPTED, filter);
        gateway.subscribe("gateway-feat017-response", AgentBusEventType.INVOCATION_RESPONSE, filter);
        responses.forEach(message -> assertThat(responseBus
                .produce(message, Feat017BusIntegrationSupport.NOW)
                .outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ACCEPTED));

        List<BrokerInboundMessage> received = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            BrokerInboundMessage message = gateway.poll(Feat017BusIntegrationSupport.NOW).orElseThrow();
            received.add(message);
            gateway.commit(message);
        }
        return received;
    }
}
