/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.RocketMqBrokerForwardingRelay;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingReceipt;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;
import com.openjiuwen.bus.forwarding.test.InMemoryForwardingOutbox;
import com.openjiuwen.bus.gateway.runtime.FakeAgentDiscoveryService;
import com.openjiuwen.bus.gateway.runtime.GatewayRuntimeService;
import com.openjiuwen.bus.spi.ingress.IngressEnvelope;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * S7 prototype — real-RocketMQ PRODUCE-side integration test for FEAT-013/014
 * (REQ-2026-001 集成联调, env-guarded).
 *
 * <p>Validates the S2/S3 produce path against a real broker:
 * {@link GatewayRuntimeService#dispatchRequest} → {@link RocketMqBrokerForwardingRelay}
 * (real {@link DefaultMQProducer} via {@link RocketMqBrokerForwardingRelay#defaultSender})
 * → broker topic, drained by a temp {@link DefaultMQPushConsumer} that mimics the
 * consume side (S4 receiver consumer is deferred, so this is the联调 bridge). Asserts
 * the message lands on the right topic with all routing user-properties
 * (tenantId/messageId/sourceServiceId/targetServiceId/correlationId/eventType/payloadRef)
 * and that the first-class control fields round-trip across the broker hop.
 *
 * <p><b>Env-guarded.</b> Skipped unless the {@code ROCKETMQ_NAMESERVER} environment
 * variable is set (no broker → skip → suite stays green). Run against a live broker with:
 * <pre>{@code
 *   ROCKETMQ_NAMESERVER=<host>:9876 ../mvnw test -Dtest=RealBrokerProduceSideIntegrationTest
 * }</pre>
 * On Windows Git Bash: {@code ROCKETMQ_NAMESERVER=7.213.203.4:9876 ../mvnw test -Dtest=...}.
 *
 * <p>Uses JUnit-native {@link EnabledIfEnvironmentVariable} (NOT Spring's
 * {@code @EnabledIfEnvironmentProperty}) because agent-bus has no {@code @SpringBootTest}
 * anywhere — every test is plain JUnit 5, and the Spring annotation is not evaluated
 * without a {@code SpringExtension}. Same env-guard intent, framework-independent.
 *
 * <p>UC coverage (handoff UC table): UC-2 (client invocation produce — the full
 * gateway path) + UC-3 (A2A call request produce). UC-3 is driven DIRECTLY via
 * {@code relay.produce} (not {@code gateway.dispatchRequest}) because the S2
 * client-ingress gateway only maps {@code IngressRequestType} →
 * {@code CLIENT_INVOCATION_*}; {@code A2A_CALL_REQUESTED} is not reachable through the
 * client-ingress path (A2A ingress is deferred to S4). This gap between the handoff
 * UC table and the implemented S2 gateway is a联调-discovered finding.
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md} §4.2 / §5.2;
 * {@code architecture/L2-Low-Level-Design/agent-bus/feat-014-a2a-call-event-forwarding.md} §5.2.
 */
// scope: forwarding transport.broker test — real-RocketMQ produce-side IT (env-guarded, S7 prototype)
@EnabledIfEnvironmentVariable(named = "ROCKETMQ_NAMESERVER", matches = ".+")
@Isolated
class RealBrokerProduceSideIntegrationTest {
    private static final String TENANT = "tenant-a";
    private static final String GATEWAY = "it-gateway";
    private static final String RUNTIME = "it-agent-runtime";
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String ROUTE_INVOCATION = "route-invocation";
    private static final String ROUTE_A2A = "route-a2a";

    // RocketMQ topic-name validator (^[%|a-zA-Z0-9_-]+$) forbids '.' — 联调 surfaced
    // that the L2 §5.2 + docker-compose.yml originally used dotted names
    // (ascend.bus.invocation.req), which mqadmin rejects. Reconciled to underscore
    // (ascend_bus_invocation_req) across the L2 docs + docker-compose + this IT.
    private static final String TOPIC_INVOCATION_REQ = "ascend_bus_invocation_req";
    private static final String TOPIC_A2A_REQ = "ascend_bus_a2a_req";
    private static final long CAPTURE_TIMEOUT_MS = 10_000L;

    private static DefaultMQProducer producer;
    private static DefaultMQPushConsumer drainConsumer;
    private static final ConcurrentLinkedQueue<MessageExt> captured = new ConcurrentLinkedQueue<>();

    private InMemoryForwardingOutbox outbox;
    private RocketMqBrokerForwardingRelay relay;
    private GatewayRuntimeService gateway;

    @BeforeAll
    static void startBrokerClients() throws Exception {
        String nameserver = System.getenv("ROCKETMQ_NAMESERVER");
        producer = new DefaultMQProducer("it-gateway-producer");
        producer.setNamesrvAddr(nameserver);
        producer.start();

        // Temp drain consumer (S4 receiver consumer is deferred). Subscribes to the two
        // request topics and captures produced messages so the test can assert them.
        drainConsumer = new DefaultMQPushConsumer("it-drain-consumer");
        drainConsumer.setNamesrvAddr(nameserver);
        drainConsumer.subscribe(TOPIC_INVOCATION_REQ, "*");
        drainConsumer.subscribe(TOPIC_A2A_REQ, "*");
        drainConsumer.registerMessageListener((MessageListenerConcurrently) (msgs, ctx) -> {
            captured.addAll(msgs);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        drainConsumer.start();
        // Default CONSUME_FROM_LAST_OFFSET consumes only messages produced AFTER the
        // consumer is assigned queues; let rebalance settle before producing.
        Thread.sleep(3_000L);
    }

    @AfterAll
    static void shutdownBrokerClients() {
        if (drainConsumer != null) {
            drainConsumer.shutdown();
        }
        if (producer != null) {
            producer.shutdown();
        }
    }

    @BeforeEach
    void wireGateway() {
        captured.clear();
        outbox = new InMemoryForwardingOutbox();
        DefaultBrokerTopicResolver resolver = new DefaultBrokerTopicResolver();
        relay = new RocketMqBrokerForwardingRelay(
                resolver, "req", RocketMqBrokerForwardingRelay.defaultSender(producer));
        // Produce-side tests do not poll responses; a no-op responseConsumer satisfies the ctor.
        BrokerForwardingConsumerPort noopConsumer = new BrokerForwardingConsumerPort() {
            @Override
            public void subscribe(String consumerServiceId, AgentBusEventType eventType, DeliveryFilter filter) {
                // produce-side IT never polls responses; no-op.
            }
            @Override
            public Optional<BrokerInboundMessage> poll(long nowMillisEpoch) {
                return Optional.empty();
            }
            @Override
            public void commit(BrokerInboundMessage message) {
                // no-op
            }
            @Override
            public void reject(BrokerInboundMessage message, ForwardingFailureCode code) {
                // no-op
            }
            @Override
            public void close() {
                // no-op
            }
            @Override
            public boolean supportsBrokerSidePropertyFilter() {
                return false;
            }
        };
        // Discovery fake: register the runtime card so dispatchRequest can resolve the target
        // (serviceId=RUNTIME → routeHandle=ROUTE_INVOCATION, which UC-2 asserts on the descriptor).
        FakeAgentDiscoveryService discovery = new FakeAgentDiscoveryService()
                .register("agent-runtime", RUNTIME, ROUTE_INVOCATION, "a2a");
        gateway = new GatewayRuntimeService(outbox, outbox, relay, noopConsumer, discovery,
                GATEWAY, 5_000L, 30_000L, System::currentTimeMillis);
    }

    /**
     * UC-2 — a client RUN_CREATE request dispatched through the gateway lands on the FEAT-013 request
     * topic with first-class control user-properties and the A2A body inlined (P-06): the control plane
     * rides its OWN properties (no descriptor token in payloadRef) and the body is NOT dropped.
     *
     * @throws Exception if the gateway dispatch, broker produce, or capture fails
     */
    @Test
    void uc2_clientInvocationRequest_landsOnBrokerWithControlAndInlinedBody() throws Exception {
        UUID requestId = UUID.randomUUID();
        IngressEnvelope env = new IngressEnvelope(
                requestId, TENANT, UUID.randomUUID(),
                IngressEnvelope.IngressRequestType.RUN_CREATE,
                "payload-body", TRACE, /* deadlineMillisEpoch */ null,
                Map.of("routeFamily", "invocation", "serviceId", RUNTIME));

        ForwardingEnvelope envelope = gateway.dispatchRequest(env);

        MessageExt received = awaitCapture(envelope.correlationId());
        assertThat(received.getTopic()).isEqualTo(TOPIC_INVOCATION_REQ);
        assertThat(received.getProperty("tenantId")).isEqualTo(TENANT);
        assertThat(received.getProperty("messageId")).isEqualTo(envelope.messageId().value());
        assertThat(received.getProperty("sourceServiceId")).isEqualTo(GATEWAY);
        assertThat(received.getProperty("targetServiceId")).isEqualTo(RUNTIME);
        assertThat(received.getProperty("correlationId")).isEqualTo(requestId.toString());
        assertThat(received.getProperty("eventType")).isEqualTo("CLIENT_INVOCATION_REQUESTED");
        // P-06: control plane rides FIRST-CLASS user properties (no descriptor token in payloadRef)
        assertThat(received.getProperty("traceId")).isEqualTo(TRACE);
        assertThat(received.getProperty("idempotencyKey")).isEqualTo(env.idempotencyKey().toString());
        assertThat(received.getProperty("routeHandle")).isEqualTo(ROUTE_INVOCATION);
        assertThat(received.getProperty("capability")).isEqualTo("a2a"); // default when omitted
        // P-06 (2b): the A2A body rides inlinePayload — it is NOT dropped crossing the broker
        assertThat(received.getProperty("inlinePayload")).isEqualTo("payload-body");
        // payloadRef (the data reference) is absent when the body is inlined; body is routing descriptor only (§6.2②)
        assertThat(received.getProperty("payloadRef")).isNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo("target=" + RUNTIME);
    }

    /**
     * UC-3 — an A2A call request produced via the relay lands on the FEAT-014 A2A
     * request topic. Driven directly through {@code relay.produce} (not
     * {@code gateway.dispatchRequest}) because the S2 client-ingress gateway cannot
     * emit {@code A2A_CALL_REQUESTED} (A2A ingress is deferred to S4).
     *
     * @throws Exception if the relay produce or capture fails
     */
    @Test
    void uc3_a2aCallRequest_producedViaRelay_landsOnA2aTopic() throws Exception {
        String correlationId = "a2a-" + UUID.randomUUID();
        String idempotencyKey = "idem-" + UUID.randomUUID();
        // P-06: control plane rides the envelope's first-class control fields; payloadRef is the A2A
        // data reference (no descriptor token). The 13-arg ctor sets control first-class + payloadRef.
        String dataRef = "ref://a2a-body/" + correlationId;
        ForwardingEnvelope envelope = new ForwardingEnvelope(
                new ForwardingMessageId("gw-" + UUID.randomUUID()),
                AgentBusEventType.A2A_CALL_REQUESTED, TENANT, TRACE, correlationId,
                idempotencyKey, new ForwardingRouteHandle(ROUTE_A2A, TENANT), "a2a",
                GATEWAY, RUNTIME, Long.MAX_VALUE,
                ForwardingEnvelope.PayloadPolicy.DATA_BEARING, dataRef);

        ForwardingReceipt receipt = outbox.enqueue(envelope, GATEWAY, RUNTIME, System.currentTimeMillis());
        assertThat(receipt).isNotNull();
        List<ForwardingOutboxRecord> claimed = outbox.claimDue(
                TENANT, System.currentTimeMillis(), 1, GATEWAY,
                System.currentTimeMillis() + 60_000L);
        assertThat(claimed).hasSize(1);

        BrokerProduceOutcome outcome = relay.produce(claimed.get(0), System.currentTimeMillis());
        assertThat(outcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ACCEPTED);

        MessageExt received = awaitCapture(correlationId);
        assertThat(received.getTopic()).isEqualTo(TOPIC_A2A_REQ);
        assertThat(received.getProperty("eventType")).isEqualTo("A2A_CALL_REQUESTED");
        assertThat(received.getProperty("correlationId")).isEqualTo(correlationId);
        assertThat(received.getProperty("tenantId")).isEqualTo(TENANT);
        assertThat(received.getProperty("sourceServiceId")).isEqualTo(GATEWAY);
        assertThat(received.getProperty("targetServiceId")).isEqualTo(RUNTIME);
        // P-06: control + data ride FIRST-CLASS user properties (no descriptor token in payloadRef)
        assertThat(received.getProperty("traceId")).isEqualTo(TRACE);
        assertThat(received.getProperty("routeHandle")).isEqualTo(ROUTE_A2A);
        assertThat(received.getProperty("payloadRef")).isEqualTo(dataRef);
    }

    /**
     * Drain the captured queue for the message whose correlationId user-property matches.
     *
     * @param correlationId the correlation id to match against the captured messages
     * @return the first captured message whose correlationId matches
     * @throws InterruptedException if the poll sleep is interrupted
     */
    private MessageExt awaitCapture(String correlationId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            for (MessageExt m : captured) {
                if (correlationId.equals(m.getProperty("correlationId"))) {
                    return m;
                }
            }
            Thread.sleep(100L);
        }
        throw new AssertionError("no captured message with correlationId=" + correlationId
                + " within " + CAPTURE_TIMEOUT_MS + "ms; captured total=" + captured.size());
    }
}
