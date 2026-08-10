/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.RocketMqBrokerForwardingConsumer;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.RocketMqBrokerForwardingProducer;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;
import com.openjiuwen.service.bus.consumer.runtime.AgentBusBrokerDeliveryPort;
import com.openjiuwen.service.bus.consumer.runtime.BrokerDeliveryLoop;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Isolated;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the FEAT-017 ownership slice against a live RocketMQ through the real agent-bus SDK adapters.
 *
 * <p>The test sends a request to {@code *_deliver}, lets FEAT-017 consume and invoke its A2A bridge,
 * then sends the durable response projections to {@code *_resp_in}. FEAT-013/014 own the surrounding
 * {@code *_req -> *_deliver} and {@code *_resp_in -> *_resp_out} relay hops.
 *
 * @since 2026-07-22
 */
@EnabledIfEnvironmentVariable(named = "ROCKETMQ_NAMESERVER", matches = ".+")
@Isolated
class RealRocketMqFeat017IntegrationTest {
    private static final long POLL_WAIT_MILLIS = 5_000L;

    private static DefaultMQProducer producer;
    private static RocketMqBrokerForwardingProducer requestProducer;
    private static RocketMqBrokerForwardingProducer responseProducer;
    private static RocketMqBrokerForwardingConsumer responseConsumer;
    private static AgentBusBrokerDeliveryPort runtimeDelivery;
    private static String runtimeConsumerGroup;
    private static String runtimeServiceId;
    private static String gatewayServiceId;

    @BeforeAll
    static void startBrokerClients() throws Exception {
        String nameserver = System.getenv("ROCKETMQ_NAMESERVER");
        String runId = UUID.randomUUID().toString().substring(0, 8);
        runtimeConsumerGroup = Feat017BusIntegrationSupport.RUNTIME_CONSUMER + "-" + runId;
        runtimeServiceId = Feat017BusIntegrationSupport.RUNTIME + "-" + runId;
        gatewayServiceId = Feat017BusIntegrationSupport.GATEWAY + "-" + runId;

        producer = new DefaultMQProducer("feat017-it-producer-" + runId);
        producer.setNamesrvAddr(nameserver);
        producer.start();
        requestProducer = new RocketMqBrokerForwardingProducer(new DefaultBrokerTopicResolver(), "deliver",
                RocketMqBrokerForwardingProducer.defaultSender(producer));
        responseProducer = new RocketMqBrokerForwardingProducer(new DefaultBrokerTopicResolver(), "resp_in",
                RocketMqBrokerForwardingProducer.defaultSender(producer));

        var requestConsumer = new RocketMqBrokerForwardingConsumer(new DefaultBrokerTopicResolver(), "deliver",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(nameserver), POLL_WAIT_MILLIS);
        runtimeDelivery = new AgentBusBrokerDeliveryPort(requestConsumer, runtimeConsumerGroup,
                Feat017BusIntegrationSupport.TENANT, runtimeServiceId);

        responseConsumer = new RocketMqBrokerForwardingConsumer(new DefaultBrokerTopicResolver(), "resp_in",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(nameserver), POLL_WAIT_MILLIS);
        String responseGroup = "feat017-bus-response-" + runId;
        DeliveryFilter responseFilter = DeliveryFilter.forRuntime(Feat017BusIntegrationSupport.TENANT,
                gatewayServiceId);
        responseConsumer.subscribe(responseGroup, AgentBusEventType.INVOCATION_ACCEPTED, responseFilter);
        responseConsumer.subscribe(responseGroup, AgentBusEventType.INVOCATION_RESPONSE, responseFilter);
        responseConsumer.subscribe(responseGroup, AgentBusEventType.A2A_CALL_ACCEPTED, responseFilter);
        responseConsumer.subscribe(responseGroup, AgentBusEventType.A2A_CALL_RESPONSE, responseFilter);

        Thread.sleep(3_000L);
    }

    @AfterAll
    static void stopBrokerClients() {
        if (runtimeDelivery != null) {
            runtimeDelivery.close();
        }
        if (responseConsumer != null) {
            responseConsumer.close();
        }
        if (producer != null) {
            producer.shutdown();
        }
    }

    @Test
    void invocationRequestTraversesDeliverRuntimeAndResponseIngress() {
        runRoundTrip(AgentBusEventType.CLIENT_INVOCATION_REQUESTED, AgentBusEventType.INVOCATION_ACCEPTED,
                AgentBusEventType.INVOCATION_RESPONSE, "invocation");
    }

    @Test
    void a2aRequestTraversesDeliverRuntimeAndResponseIngress() {
        runRoundTrip(AgentBusEventType.A2A_CALL_REQUESTED, AgentBusEventType.A2A_CALL_ACCEPTED,
                AgentBusEventType.A2A_CALL_RESPONSE, "a2a");
    }

    @Test
    void queryUsesRuntimeTaskIdNotClientInvocationId() {
        String correlationId = "feat017-query-" + UUID.randomUUID();
        String taskId = "runtime-task-" + UUID.randomUUID();
        String clientInvocationId = "client-invocation-" + UUID.randomUUID();
        var taskStore = new Feat017BusIntegrationSupport.RecordingTaskStore();
        taskStore.save(Feat017BusIntegrationSupport.completedTask(taskId, "query-context"), true);
        taskStore.save(Feat017BusIntegrationSupport.completedTask(clientInvocationId, "decoy-context"), true);
        var runtime = Feat017BusIntegrationSupport.queryRuntime(responseProducer, taskStore,
                Feat017BusIntegrationSupport.queryPayload(taskId, clientInvocationId), runtimeServiceId);
        var loop = new BrokerDeliveryLoop(runtimeDelivery,
                delivery -> runtime.consume(delivery.envelope(), delivery.payload()), runtimeConsumerGroup,
                Feat017BusIntegrationSupport.TENANT,
                Clock.fixed(Instant.ofEpochMilli(Feat017BusIntegrationSupport.NOW), ZoneOffset.UTC));

        BrokerProduceOutcome requestOutcome = requestProducer.produce(Feat017BusIntegrationSupport.requestRecord(
                AgentBusEventType.CLIENT_INVOCATION_QUERY_REQUESTED, "request-" + UUID.randomUUID(), correlationId,
                "idem-" + UUID.randomUUID(),
                new Feat017BusIntegrationSupport.ServiceEndpoints(gatewayServiceId, runtimeServiceId)),
                Feat017BusIntegrationSupport.NOW);
        assertThat(requestOutcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ACCEPTED);
        awaitDelivery(loop);
        assertThat(taskStore.requestedIds()).containsExactly(taskId);

        List<BrokerInboundMessage> busIngress = pollResponses(correlationId, 1);
        assertThat(busIngress).singleElement().satisfies(message -> {
            assertThat(message.eventType()).isEqualTo(AgentBusEventType.INVOCATION_RESPONSE);
            assertThat(message.correlationId()).isEqualTo(correlationId);
            assertThat(message.payloadRef()).isNull();
            assertThat(message.inlinePayload()).contains("taskId=" + taskId).doesNotContain(clientInvocationId);
        });
    }

    private static void runRoundTrip(AgentBusEventType requestType, AgentBusEventType acceptedType,
            AgentBusEventType responseType, String suffix) {
        String correlationId = "feat017-" + suffix + "-" + UUID.randomUUID();
        String idempotencyKey = "idem-" + UUID.randomUUID();
        AtomicInteger bridgeCalls = new AtomicInteger();
        var runtime = Feat017BusIntegrationSupport.runtime(responseProducer, bridgeCalls, runtimeServiceId);
        var loop = new BrokerDeliveryLoop(runtimeDelivery,
                delivery -> runtime.consume(delivery.envelope(), delivery.payload()), runtimeConsumerGroup,
                Feat017BusIntegrationSupport.TENANT,
                Clock.fixed(Instant.ofEpochMilli(Feat017BusIntegrationSupport.NOW), ZoneOffset.UTC));

        BrokerProduceOutcome requestOutcome = requestProducer.produce(Feat017BusIntegrationSupport.requestRecord(
                requestType, "request-" + UUID.randomUUID(), correlationId, idempotencyKey,
                new Feat017BusIntegrationSupport.ServiceEndpoints(gatewayServiceId, runtimeServiceId)),
                Feat017BusIntegrationSupport.NOW);
        assertThat(requestOutcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ACCEPTED);
        awaitDelivery(loop);
        assertThat(bridgeCalls).hasValue(1);

        List<BrokerInboundMessage> busIngress = pollResponses(correlationId, 2);
        // ISSUE-008 修复 E：两条响应可能落不同队列、跨队列顺序不保证；仅断集合，不断顺序。
        assertThat(busIngress).extracting(BrokerInboundMessage::eventType)
                .containsExactlyInAnyOrder(acceptedType, responseType);
        assertThat(busIngress).allSatisfy(message -> {
            assertThat(message.correlationId()).isEqualTo(correlationId);
            assertThat(message.targetServiceId()).isEqualTo(gatewayServiceId);
            assertThat(message.payloadRef()).isNull();
            assertThat(message.inlinePayload()).contains("taskId=");
        });
    }

    private static List<BrokerInboundMessage> pollResponses(String correlationId, int expected) {
        List<BrokerInboundMessage> matched = new ArrayList<>();
        int attempts = 0;
        while (matched.size() < expected && attempts++ < expected + 4) {
            BrokerInboundMessage message = responseConsumer.poll(System.currentTimeMillis()).orElse(null);
            if (message == null) {
                continue;
            }
            responseConsumer.commit(message);
            if (correlationId.equals(message.correlationId())) {
                matched.add(message);
            }
        }
        assertThat(matched).as("responses for correlationId=%s", correlationId).hasSize(expected);
        return matched;
    }

    private static void awaitDelivery(BrokerDeliveryLoop loop) {
        int attempts = 0;
        while (attempts++ < 8) {
            if (loop.tick()) {
                return;
            }
        }
        throw new AssertionError("runtime did not receive a matching broker delivery");
    }
}
