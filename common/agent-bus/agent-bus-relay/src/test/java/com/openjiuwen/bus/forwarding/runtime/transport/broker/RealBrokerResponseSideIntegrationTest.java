/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.RocketMqBrokerForwardingConsumer;
import com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq.RocketMqBrokerForwardingRelay;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;
import com.openjiuwen.bus.forwarding.test.InMemoryForwardingOutbox;
import com.openjiuwen.bus.gateway.runtime.FakeAgentDiscoveryService;
import com.openjiuwen.bus.gateway.runtime.GatewayRuntimeService;
import com.openjiuwen.bus.spi.ingress.IngressEnvelope;
import com.openjiuwen.bus.spi.ingress.IngressResponse;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S7 prototype — real-RocketMQ RESPONSE-side integration test for FEAT-013/014
 * (REQ-2026-001 集成联调, env-guarded).
 *
 * <p>Closes the response loop the produce-side IT ({@link RealBrokerProduceSideIntegrationTest})
 * opened. {@link GatewayRuntimeService#dispatchRequest} produces the request onto
 * {@code ascend_bus_invocation_req}; a temp agent-runtime consumer (mimicking
 * {@code TestAgentRuntime}) consumes it and produces its response sequence onto
 * {@code ascend_bus_invocation_resp_out}; a real {@link BrokerForwardingConsumerPort}
 * (LitePull — broker-side bySql filter, model B) drains those responses;
 * {@link GatewayRuntimeService#acceptWindow} polls, classifies, and returns the observable
 * {@link IngressResponse} per L2 feat-013 §6.
 *
 * <p><b>§6 D4 hard proof (slice 3 migration).</b> The response-side + runtime consumers are
 * {@link RocketMqBrokerForwardingConsumer} LitePull adapters (BrokerForwardingConsumerPort) — the
 * test no longer touches {@code DefaultMQPushConsumer} / {@code MessageListenerConcurrently} /
 * {@code MessageExt} (the consumer surface is fully behind the SPI), proving prod encapsulation
 * holds. The gateway consumes via its adapter directly (model B ack-after-consume — a queue between
 * poll and commit would break the ack chain); a separate verifier adapter ({@code it-gateway-verify},
 * different consumer-group) backs {@link #pollNext} / UC-10 so they read the same responses without
 * contending with acceptWindow. The temp runtime runs a LitePull poll-loop (request consumption).
 *
 * <p><b>Blocking poll — why.</b> {@link GatewayRuntimeService#acceptWindow} is a synchronous drain:
 * {@code if (msg == null) break;} exits on the first empty poll. Against a real broker responses
 * arrive asynchronously (Windows gateway → Linux broker → temp runtime → broker → Windows
 * consumer), so the adapter's poll BLOCKS up to {@code pollWaitMs} for each message (the honest
 * representation of a real receiver consumer); a non-blocking drain would return empty before the
 * temp runtime has produced anything → spurious DEFERRED.
 *
 * <p><b>D9 verify.</b> {@link #d9_bysql_filter_is_enforced_broker_side} subscribes a consumer with
 * {@code targetServiceId='nobody'} and produces a message targeted elsewhere — the consumer pulls 0,
 * proving the bySql filter is enforced BROKER-SIDE (not a silent client-side degrade; requires
 * {@code enablePropertyFilter=true}, slice 4).
 *
 * <p><b>Env-guarded.</b> Skipped unless {@code ROCKETMQ_NAMESERVER} is set. Run against a live broker:
 * <pre>{@code
 *   ROCKETMQ_NAMESERVER=7.213.203.4:9876 ../mvnw test -Dtest=RealBrokerResponseSideIntegrationTest
 * }</pre>
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md} §4.2 / §4.3 / §6.2.1 / §6.2.3 / §6.2.4;
 * {@code feat-014-a2a-call-event-forwarding.md} §4.4; decision packet §3 / §6 D4 / D9 / D14.
 */
// scope: forwarding transport.broker test — real-RocketMQ response-side IT (env-guarded, S7 prototype)
@EnabledIfEnvironmentVariable(named = "ROCKETMQ_NAMESERVER", matches = ".+")
@Isolated
class RealBrokerResponseSideIntegrationTest {
    private static final String TENANT = "tenant-a";
    private static final String GATEWAY = "it-gateway";
    private static final String RUNTIME = "it-agent-runtime";

    // distinct consumer-GROUPS so A (acceptWindow) and V (verifier) each receive every response
    // (same group would load-balance queues → each misses what the other got).
    private static final String GROUP_RESP = "it-gateway-resp";
    private static final String GROUP_VERIFY = "it-gateway-verify";
    private static final String TRACE = "0123456789abcdef0123456789abcdef";
    private static final String ROUTE_INVOCATION = "route-invocation";
    private static final String ROUTE_A2A = "route-a2a";
    private static final String TOPIC_INVOCATION_REQ = "ascend_bus_invocation_req";
    private static final String TOPIC_A2A_REQ = "ascend_bus_a2a_req";
    private static final String TOPIC_INVOCATION_RESP_OUT = "ascend_bus_invocation_resp_out";
    private static final String TOPIC_A2A_RESP_OUT = "ascend_bus_a2a_resp_out";

    private static final long ACCEPT_TIMEOUT_MS = 15_000L;
    private static final long RESPONSE_TIMEOUT_MS = 30_000L;

    // the adapter's poll blocks up to this long for an async message (broker round-trip latency budget).
    private static final long POLL_WAIT_MS = 5_000L;

    // produce-side (gateway dispatch) + the three LitePull consumer adapters:
    // A=gateway resp, V=verifier (separate group → receives every response), T=temp runtime req.
    private static DefaultMQProducer gatewayProducer;
    private static RocketMqBrokerForwardingConsumer responseAdapter; // A: acceptWindow polls this (model B)
    private static RocketMqBrokerForwardingConsumer verifyAdapter; // V: verifier (pollNext / UC-10)
    private static TempRuntime tempRuntime; // T: LitePull poll-loop consuming requests

    private GatewayRuntimeService gateway;

    /**
     * P-06: extract a {@code key=value} token from a response inlinePayload (A2A response content).
     *
     * @param body the response inlinePayload (nullable; null/blank → null)
     * @param key the token key to match
     * @return the token value, or {@code null} if absent
     */
    private static String inlineToken(String body, String key) {
        String result = null;
        if (body != null && !body.isBlank()) {
            for (String pair : body.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && pair.substring(0, eq).equals(key)) {
                    result = pair.substring(eq + 1);
                    break;
                }
            }
        }
        return result;
    }

    @BeforeAll
    static void startBrokerClients() throws Exception {
        String nameserver = System.getenv("ROCKETMQ_NAMESERVER");

        gatewayProducer = new DefaultMQProducer("it-gateway-producer");
        gatewayProducer.setNamesrvAddr(nameserver);
        gatewayProducer.start();

        DefaultBrokerTopicResolver respResolver = new DefaultBrokerTopicResolver();
        DeliveryFilter respFilter = DeliveryFilter.forRuntime(TENANT, GATEWAY);

        // A — the gateway's response consumer (acceptWindow drains this directly; model B commit).
        responseAdapter = new RocketMqBrokerForwardingConsumer(
                respResolver, "resp_out",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(nameserver), POLL_WAIT_MS);
        responseAdapter.subscribe(GROUP_RESP, AgentBusEventType.INVOCATION_RESPONSE, respFilter);
        responseAdapter.subscribe(GROUP_RESP, AgentBusEventType.A2A_CALL_RESPONSE, respFilter);

        // V — verifier (different consumer-group → receives every response, independently of A's drain).
        verifyAdapter = new RocketMqBrokerForwardingConsumer(
                respResolver, "resp_out",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(nameserver), POLL_WAIT_MS);
        verifyAdapter.subscribe(GROUP_VERIFY, AgentBusEventType.INVOCATION_RESPONSE, respFilter);
        verifyAdapter.subscribe(GROUP_VERIFY, AgentBusEventType.A2A_CALL_RESPONSE, respFilter);

        tempRuntime = new TempRuntime(nameserver);
        tempRuntime.start();

        // CONSUME_FROM_LAST_OFFSET: a consumer only sees messages produced AFTER it is assigned queues;
        // let rebalance settle (A + V + T) before any test dispatches.
        Thread.sleep(3_000L);
    }

    @AfterAll
    static void shutdownBrokerClients() {
        if (tempRuntime != null) {
            tempRuntime.shutdown();
        }
        if (responseAdapter != null) {
            responseAdapter.close();
        }
        if (verifyAdapter != null) {
            verifyAdapter.close();
        }
        if (gatewayProducer != null) {
            gatewayProducer.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        tempRuntime.setResponseMode(TempRuntime.ResponseMode.BLOCKING);
        wireGateway(ACCEPT_TIMEOUT_MS, RESPONSE_TIMEOUT_MS);
    }

    /**
     * Wire the gateway with per-test accept/response timeouts; the response consumer is the shared
     * adapter A.
     *
     * @param acceptTimeoutMs  per-test accept window (no accepted within → UNKNOWN)
     * @param responseTimeoutMs per-test post-accepted window (no terminal within → ACCEPTED_WITH_TASK)
     */
    private void wireGateway(long acceptTimeoutMs, long responseTimeoutMs) {
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        DefaultBrokerTopicResolver resolver = new DefaultBrokerTopicResolver();
        RocketMqBrokerForwardingRelay relay = new RocketMqBrokerForwardingRelay(
                resolver, "req", RocketMqBrokerForwardingRelay.defaultSender(gatewayProducer));
        // Discovery fake: register the runtime card so dispatchRequest can resolve the target
        // (serviceId=RUNTIME → routeHandle=ROUTE_INVOCATION, which the test envelopes assert).
        FakeAgentDiscoveryService discovery = new FakeAgentDiscoveryService()
                .register("agent-runtime", RUNTIME, ROUTE_INVOCATION, "a2a");
        gateway = new GatewayRuntimeService(outbox, outbox, relay, responseAdapter, discovery,
                GATEWAY, acceptTimeoutMs, responseTimeoutMs, System::currentTimeMillis);
    }

    /**
     * Drain the verifier (V) for the next response whose {@code correlationId} matches
     * {@code expectedCorrId} (blocking). Polling by corrId disambiguates the two TERMINAL variants
     * and skips leftovers from prior tests — V (a separate group) accumulates every response, so
     * non-matching ones are committed + skipped here.
     *
     * @param expectedCorrId the correlationId to match (== requestId.toString())
     * @return the next matching response (committed out of V's queue by the caller)
     */
    private BrokerInboundMessage pollNext(String expectedCorrId) {
        long deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            BrokerInboundMessage msg = verifyAdapter.poll(System.currentTimeMillis()).orElse(null);
            if (msg == null) {
                break; // V's poll timed out (POLL_WAIT_MS) with no message
            }
            if (expectedCorrId.equals(msg.correlationId())) {
                return msg;
            }
            verifyAdapter.commit(msg); // leftover / different corrId — advance V and keep polling
        }
        throw new AssertionError("no response with correlationId=" + expectedCorrId
                + " polled within " + ACCEPT_TIMEOUT_MS + "ms");
    }

    private IngressEnvelope runCreate(UUID requestId) {
        return new IngressEnvelope(
                requestId, TENANT, UUID.randomUUID(),
                IngressEnvelope.IngressRequestType.RUN_CREATE,
                "payload-body", TRACE, /* deadlineMillisEpoch */ null,
                Map.of("routeFamily", "invocation", "serviceId", RUNTIME));
    }

    /**
     * UC-4 — blocking call (L2 §6.2.1). The temp runtime consumes the dispatched RUN_CREATE
     * and produces BLOCKING (ACCEPTED + RESPONSE + TERMINAL(completed)). acceptWindow records
     * the ACCEPTED taskId, then classifies the RESPONSE as COMPLETED_RESPONSE →
     * {@link IngressResponse#accepted} with {@code cursor=taskId}.
     */
    @Test
    void uc4_blocking_call_returns_completed_accepted() {
        UUID requestId = UUID.randomUUID();
        gateway.dispatchRequest(runCreate(requestId));
        IngressResponse resp = gateway.acceptWindow(requestId, TENANT);

        assertThat(resp.status()).isEqualTo(IngressResponse.IngressStatus.ACCEPTED);
        assertThat(resp.cursor()).startsWith("task-");
        assertThat(resp.rejectionReason()).isNull();
    }

    /**
     * UC-5 — timeout/degraded (L2 §6.2.3). The temp runtime is SILENT (creates task, emits
     * nothing); acceptWindow polls up to the (short) accept window, drains empty → UNKNOWN →
     * {@link IngressResponse#deferred}. A short acceptTimeoutMs keeps the test fast.
     */
    @Test
    void uc5_silent_timeout_returns_deferred() {
        tempRuntime.setResponseMode(TempRuntime.ResponseMode.SILENT);
        wireGateway(2_000L, 5_000L);
        UUID requestId = UUID.randomUUID();
        gateway.dispatchRequest(runCreate(requestId));
        IngressResponse resp = gateway.acceptWindow(requestId, TENANT);

        assertThat(resp.status()).isEqualTo(IngressResponse.IngressStatus.DEFERRED);
        assertThat(resp.cursor()).isNull();
        assertThat(resp.rejectionReason()).isNull();
    }

    /**
     * UC-6 — classify each response eventType from a real broker-surfaced message. The
     * eventType user-property must survive the broker round-trip (produce → LitePull consume →
     * {@link BrokerInboundMessage}); {@link GatewayRuntimeService#classify} then maps it.
     */
    @Test
    void uc6_classify_each_event_type_from_real_broker() {
        assertClassify(AgentBusEventType.INVOCATION_ACCEPTED, "taskId=t1",
                InvocationResponseStatus.ACCEPTED_WITH_TASK);
        assertClassify(AgentBusEventType.INVOCATION_RESPONSE, "taskId=t2;status=snapshot",
                InvocationResponseStatus.COMPLETED_RESPONSE);
        assertClassify(AgentBusEventType.INVOCATION_STREAM_READY, "taskId=t3;streamRef=stream://t3",
                InvocationResponseStatus.STREAM_READY);
        assertClassify(AgentBusEventType.INVOCATION_REJECTED, "reason=denied",
                InvocationResponseStatus.REJECTED);
        assertClassify(AgentBusEventType.INVOCATION_FAILED, "reason=boom",
                InvocationResponseStatus.FAILED);
        assertClassify(AgentBusEventType.INVOCATION_TERMINAL, "taskId=t4;status=completed",
                InvocationResponseStatus.COMPLETED_RESPONSE);
        assertClassify(AgentBusEventType.INVOCATION_TERMINAL, "taskId=t5;status=failed",
                InvocationResponseStatus.FAILED);
    }

    /**
     * Produce one response of {@code eventType}, poll it by corrId, assert classify maps it.
     *
     * @param eventType the response event type to produce + classify
     * @param payloadRef the response payloadRef (carrying status / taskId / streamRef tokens)
     * @param expected   the classification {@code gateway.classify} should yield
     */
    private void assertClassify(AgentBusEventType eventType, String payloadRef,
                                InvocationResponseStatus expected) {
        String corrId = UUID.randomUUID().toString();
        tempRuntime.produceResponse(eventType, payloadRef, corrId);
        assertThat(gateway.classify(pollNext(corrId))).isEqualTo(expected);
    }

    /**
     * UC-7 — streaming (L2 §6.2.4). The temp runtime is STREAMING (ACCEPTED + STREAM_READY);
     * acceptWindow records the ACCEPTED taskId, then classifies STREAM_READY →
     * {@link IngressResponse#accepted} with {@code cursor=streamRef}.
     */
    @Test
    void uc7_streaming_returns_accepted_with_stream_ref_cursor() {
        tempRuntime.setResponseMode(TempRuntime.ResponseMode.STREAMING);
        UUID requestId = UUID.randomUUID();
        gateway.dispatchRequest(runCreate(requestId));
        IngressResponse resp = gateway.acceptWindow(requestId, TENANT);

        assertThat(resp.status()).isEqualTo(IngressResponse.IngressStatus.ACCEPTED);
        assertThat(resp.cursor()).startsWith("stream://");
    }

    /**
     * UC-8 — skip-own + non-matching corrId. A self-source response (sourceServiceId=GATEWAY,
     * matching corrId) must be skipped by acceptWindow's self-consumption guard; a non-matching
     * corrId response must be skipped by the corrId filter. With neither matched, the window
     * drains empty → DEFERRED.
     */
    @Test
    void uc8_skips_self_source_and_non_matching_corr_id() {
        wireGateway(3_000L, 5_000L);
        UUID requestId = UUID.randomUUID();
        String matched = requestId.toString();
        // self-source (matching corrId, source=GATEWAY) → skip-own
        tempRuntime.produceResponse(AgentBusEventType.INVOCATION_RESPONSE,
                "taskId=self-1;status=snapshot", matched,
                new TempRuntime.ResponseRouting(TENANT, GATEWAY, GATEWAY, TRACE,
                        "idem-self", ROUTE_INVOCATION, "a2a"));
        // non-matching corrId → corrId-filter skip
        tempRuntime.produceResponse(AgentBusEventType.INVOCATION_RESPONSE,
                "taskId=other-1;status=snapshot", UUID.randomUUID().toString());

        IngressResponse resp = gateway.acceptWindow(requestId, TENANT);
        assertThat(resp.status()).isEqualTo(IngressResponse.IngressStatus.DEFERRED);
        assertThat(resp.cursor()).isNull();
    }

    /**
     * UC-9 — tenant isolation. A cross-tenant RESPONSE (tenant-b, matching corrId) must be
     * filtered by acceptWindow's client-side tenant guard (D13) — committed + skipped, never
     * returned. With nothing matched, the window drains empty → DEFERRED.
     */
    @Test
    void uc9_tenant_isolation_filters_cross_tenant() {
        wireGateway(3_000L, 5_000L);
        UUID requestId = UUID.randomUUID();
        String matched = requestId.toString();
        // cross-tenant (tenant-b) RESPONSE with MATCHING corrId → filtered by acceptWindow's tenant guard
        tempRuntime.produceResponse(AgentBusEventType.INVOCATION_RESPONSE,
                "taskId=B-1;status=snapshot", matched,
                new TempRuntime.ResponseRouting("tenant-b", RUNTIME, GATEWAY, TRACE,
                        "idem-b", ROUTE_INVOCATION, "a2a"));

        IngressResponse resp = gateway.acceptWindow(requestId, TENANT);
        assertThat(resp.status()).isEqualTo(IngressResponse.IngressStatus.DEFERRED);
        assertThat(resp.cursor()).isNull();
    }

    /**
     * UC-10 — idempotency (L2 §4.4). Two dispatches with the same (tenantId, idempotencyKey)
     * → the temp runtime dedups (tenantId|idempotencyKey) → both responses share ONE taskId.
     * Drain the verifier for all responses matching this corrId and assert exactly one distinct
     * taskId.
     *
     * @throws Exception if interrupted while sleeping / polling for the temp runtime's responses
     */
    @Test
    void uc10_idempotency_duplicate_dispatch_emits_one_task_id() throws Exception {
        UUID requestId = UUID.randomUUID();
        UUID idempotencyKey = UUID.randomUUID();
        String corrId = requestId.toString();
        IngressEnvelope env = new IngressEnvelope(requestId, TENANT, idempotencyKey,
                IngressEnvelope.IngressRequestType.RUN_CREATE, "payload-body", TRACE, null,
                Map.of("routeFamily", "invocation", "serviceId", RUNTIME));
        gateway.dispatchRequest(env);
        gateway.dispatchRequest(env); // same (tenantId, idempotencyKey) → server-side dedup
        // let the temp runtime consume both + produce responses (cross-machine)
        Thread.sleep(4_000L);
        java.util.Set<String> taskIds = new java.util.HashSet<>();
        long drainDeadline = System.currentTimeMillis() + 6_000L;
        while (System.currentTimeMillis() < drainDeadline) {
            BrokerInboundMessage m = verifyAdapter.poll(System.currentTimeMillis()).orElse(null);
            if (m == null) {
                break;
            }
            if (corrId.equals(m.correlationId())) {
                // P-06: response content (taskId) rides inlinePayload (the A2A response envelope as DATA)
                String tid = inlineToken(m.inlinePayload(), "taskId");
                if (tid != null) {
                    taskIds.add(tid);
                }
            }
            verifyAdapter.commit(m);
        }
        assertThat(taskIds).hasSize(1);
    }

    /**
     * D9 — bySql filter is enforced BROKER-SIDE (not a silent client-side degrade; requires
     * {@code enablePropertyFilter=true}, slice 4). RIGOROUS (avoids the false-positive where pulling 0
     * is ambiguous between "broker filtered" and "consumer never received"): first a MATCHING message
     * ({@code targetServiceId='nobody'}) MUST be delivered — proving the consumer is subscribed + bySql
     * matches — then a NON-MATCHING one ({@code targetServiceId=RUNTIME}) MUST be filtered broker-side
     * (pull 0). If {@code enablePropertyFilter} were off, the broker would deliver the non-matching
     * message (the adapter does NOT client-filter — {@code supportsBrokerSidePropertyFilter()=true}),
     * surfacing the missing config as a failure rather than a silent degrade.
     *
     * @throws Exception if broker produce / poll / subscribe fails during the filter proof
     */
    @Test
    void d9_bysql_filter_is_enforced_broker_side() throws Exception {
        String nameserver = System.getenv("ROCKETMQ_NAMESERVER");
        DefaultBrokerTopicResolver respResolver = new DefaultBrokerTopicResolver();
        RocketMqBrokerForwardingConsumer nobody = new RocketMqBrokerForwardingConsumer(
                respResolver, "resp_out",
                RocketMqBrokerForwardingConsumer.defaultPollerFactory(nameserver), POLL_WAIT_MS);
        nobody.subscribe("it-nobody", AgentBusEventType.INVOCATION_RESPONSE,
                new DeliveryFilter(Map.of("targetServiceId", "nobody")));
        assertThat(nobody.supportsBrokerSidePropertyFilter()).isTrue();
        Thread.sleep(2_000L); // let rebalance settle before producing

        DefaultMQProducer producer = new DefaultMQProducer("it-d9-producer");
        producer.setNamesrvAddr(nameserver);
        producer.start();
        try {
            // 1) MATCHING: targetServiceId='nobody' → bySql matches → the broker MUST deliver it. This
            //    rules out "consumer not receiving" (subscribe/rebalance issue) as the cause of a zero pull.
            sendD9Message(producer, "resp-d9-match", "nobody");
            Optional<BrokerInboundMessage> matched = pollFirst(nobody, 6_000L);
            assertThat(matched).as("matching targetServiceId='nobody' message must be delivered").isPresent();
            nobody.commit(matched.orElseThrow());

            // 2) NON-MATCHING: targetServiceId=RUNTIME (≠ 'nobody') → the broker filters broker-side → pull 0.
            sendD9Message(producer, "resp-d9-nomatch", RUNTIME);
            assertThat(pollFirst(nobody, 4_000L))
                    .as("non-matching (target=RUNTIME) message must be filtered broker-side; receiving it "
                            + "means enablePropertyFilter is off (bySql silently degraded to client-deliver)")
                    .isEmpty();
        } finally {
            producer.shutdown();
            nobody.close();
        }
    }

    /**
     * Produce one response message to the resp topic with the given targetServiceId (used by D9).
     *
     * @param producer        the RocketMQ producer to send through
     * @param messageId       the message id (broker keys + user-property)
     * @param targetServiceId the target service id stamped on the message (the bySql filter key)
     * @throws Exception if the broker send fails
     */
    private static void sendD9Message(DefaultMQProducer producer, String messageId,
                                      String targetServiceId) throws Exception {
        Message msg = new Message(TOPIC_INVOCATION_RESP_OUT, /* tags */ null, messageId,
                ("target=" + targetServiceId).getBytes(StandardCharsets.UTF_8));
        msg.putUserProperty("tenantId", TENANT);
        msg.putUserProperty("messageId", messageId);
        msg.putUserProperty("sourceServiceId", RUNTIME);
        msg.putUserProperty("targetServiceId", targetServiceId);
        msg.putUserProperty("correlationId", UUID.randomUUID().toString());
        msg.putUserProperty("eventType", AgentBusEventType.INVOCATION_RESPONSE.name());
        msg.putUserProperty("payloadRef", "taskId=d9;status=snapshot");
        assertThat(producer.send(msg).getSendStatus()).isEqualTo(SendStatus.SEND_OK);
    }

    /**
     * Poll a consumer until it returns a message or the timeout elapses (empty → no matching message).
     *
     * @param c         the consumer to poll
     * @param timeoutMs the maximum time to wait for a message
     * @return the first polled message, or an empty Optional if the timeout elapses with no message
     */
    private static Optional<BrokerInboundMessage> pollFirst(RocketMqBrokerForwardingConsumer c, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            Optional<BrokerInboundMessage> m = c.poll(System.currentTimeMillis());
            if (m.isPresent()) {
                return m;
            }
        }
        return Optional.empty();
    }

    /**
     * Stands in for the EXTERNAL {@code agent-runtime-java} (S4 consumer lands there). A LitePull
     * poll-loop consumes request events from {@code ascend_bus_invocation_req} (broker-side bySql
     * filter = this runtime's tenant + targetServiceId), "processes" per the L2 §4.3 response state
     * machine, and produces response events back onto {@code ascend_bus_invocation_resp_out} via a
     * {@link DefaultMQProducer}, mirroring {@link RocketMqBrokerForwardingRelay#buildMessage} so the
     * response adapters read the same routing user-properties. §6 D4: request consumption is via the
     * adapter (no DefaultMQPushConsumer / MessageListener / MessageExt here).
     */
    static final class TempRuntime {
        private final RocketMqBrokerForwardingConsumer consumer; // T: LitePull adapter (request consumption)
        private final DefaultMQProducer producer; // produce responses
        private final AtomicLong taskSeq = new AtomicLong();
        private final AtomicLong respSeq = new AtomicLong();
        private final Map<String, TaskEntry> taskByKey = new ConcurrentHashMap<>();
        private volatile ResponseMode responseMode = ResponseMode.BLOCKING;
        private volatile boolean running;
        private ThreadPoolExecutor pollExecutor;

        TempRuntime(String nameserver) {
            DefaultBrokerTopicResolver reqResolver = new DefaultBrokerTopicResolver();
            this.consumer = new RocketMqBrokerForwardingConsumer(
                    reqResolver, "deliver",
                    RocketMqBrokerForwardingConsumer.defaultPollerFactory(nameserver), POLL_WAIT_MS);
            this.producer = new DefaultMQProducer("it-temp-runtime-producer");
            producer.setNamesrvAddr(nameserver);
        }

        /** Configurable response behaviour for a consumed REQUESTED event (mirrors TestAgentRuntime). */
        enum ResponseMode {BLOCKING, SILENT, STREAMING}

        /**
         * Pairs the response routing + first-class control so produceResponse stays ≤5 params (G.MET.01).
         * P-06: the response carries control first-class (the response relay's governance requires it).
         */
        record ResponseRouting(String tenantId, String source, String target,
                String traceId, String idempotencyKey, String routeHandle, String capability) {
            /**
             * Direct-injection defaults (UC-6/D9): source=RUNTIME, target=GATEWAY, standard control.
             *
             * @return a response routing with standard control fields
             */
            static ResponseRouting defaults() {
                return new ResponseRouting(TENANT, RUNTIME, GATEWAY, TRACE, "idem-stub", ROUTE_INVOCATION, "a2a");
            }
        }

        void start() throws Exception {
            producer.start();
            consumer.subscribe(RUNTIME, AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                    DeliveryFilter.forRuntime(TENANT, RUNTIME));
            consumer.subscribe(RUNTIME, AgentBusEventType.A2A_CALL_REQUESTED,
                    DeliveryFilter.forRuntime(TENANT, RUNTIME));
            running = true;
            // Single-thread executor mirroring StdioMcpClient §315 (G.CON.08/12): the daemon thread
            // carries an uncaught-exception handler; shutdown uses the executor's cooperative
            // shutdown (no Thread.interrupt — G.CON.10).
            pollExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(), new DaemonThreadFactory("it-temp-runtime-poll"));
            pollExecutor.submit(this::pollLoop);
        }

        private void pollLoop() {
            while (running) {
                try {
                    Optional<BrokerInboundMessage> msg = consumer.poll(System.currentTimeMillis());
                    if (msg.isPresent()) {
                        processRequest(msg.get());
                        consumer.commit(msg.get()); // model B ack-after-consume
                    }
                } catch (IllegalStateException e) {
                    // transient broker hiccup — keep polling (the runtime is long-lived)
                }
            }
        }

        void shutdown() {
            running = false;
            if (pollExecutor != null) {
                pollExecutor.shutdownNow();
            }
            if (consumer != null) {
                consumer.close();
            }
            if (producer != null) {
                producer.shutdown();
            }
        }

        void setResponseMode(ResponseMode mode) {
            this.responseMode = mode;
        }

        /** A created task + whether its response sequence was already emitted (§4.4 layer 2). */
        private static final class TaskEntry {
            final String taskId;
            volatile boolean emitted;

            TaskEntry(String taskId) {
                this.taskId = taskId;
            }
        }

        /** Pool-owned daemon thread factory (G.CON.08/12): threads from the default factory, never ad-hoc. */
        private static final class DaemonThreadFactory implements ThreadFactory {
            private static final ThreadFactory BASE = Executors.defaultThreadFactory();

            private final String name;

            DaemonThreadFactory(String name) {
                this.name = name;
            }

            @Override
            public Thread newThread(Runnable r) {
                Thread t = BASE.newThread(r);
                t.setName(name);
                t.setDaemon(true);
                t.setUncaughtExceptionHandler((thr, ex) -> {
                    // no-op: pollLoop already swallows transient broker hiccups
                });
                return t;
            }
        }

        /**
         * P-06: build a response routing triple (source=RUNTIME, target=the request's source) carrying
         * the request's first-class control, so produceResponse call sites stay short (G.FMT.10).
         *
         * @param tenant the tenant scope
         * @param target the response target (original caller serviceId)
         * @param req the inbound request carrying first-class control
         * @return a ResponseRouting with source=RUNTIME + the request's control fields
         */
        private ResponseRouting routing(String tenant, String target, BrokerInboundMessage req) {
            return new ResponseRouting(tenant, RUNTIME, target, req.traceId(), req.idempotencyKey(),
                    req.routeHandle(), req.capability());
        }

        private synchronized void processRequest(BrokerInboundMessage req) {
            // P-06: request control plane is FIRST-CLASS on the inbound (eventType / correlationId /
            // idempotencyKey / ...). Skip non-request echoes by eventType (own responses carry a response
            // eventType or null), not by decoding a payloadRef descriptor.
            AgentBusEventType reqEventType = req.eventType();
            if (reqEventType != AgentBusEventType.CLIENT_INVOCATION_REQUESTED) {
                return; // own response echo / out-of-scope — skip
            }
            String reqTenant = req.tenantId();
            String reqSource = req.sourceServiceId(); // = GATEWAY (response target)
            String corrId = req.correlationId();
            // §4.4 server-side creation idempotency: (tenantId|idempotencyKey) → single task.
            String dedupKey = reqTenant + "|" + req.idempotencyKey();
            TaskEntry entry = taskByKey.computeIfAbsent(dedupKey,
                    k -> new TaskEntry("task-" + taskSeq.incrementAndGet()));
            ResponseMode mode = this.responseMode;
            if (mode == ResponseMode.SILENT) {
                return; // task created, nothing emitted → gateway accept window times out (UC-5)
            }
            String taskId = entry.taskId;
            if (entry.emitted) {
                // §4.4 repeat REQUESTED → re-emit only ACCEPTED (same taskId, no second logical call)
                produceResponse(AgentBusEventType.INVOCATION_ACCEPTED, "taskId=" + taskId, corrId,
                        routing(reqTenant, reqSource, req));
                return;
            }
            entry.emitted = true;
            // L2 §6.2.1 BLOCKING: ACCEPTED + RESPONSE + TERMINAL(completed).
            produceResponse(AgentBusEventType.INVOCATION_ACCEPTED, "taskId=" + taskId, corrId,
                    routing(reqTenant, reqSource, req));
            if (mode == ResponseMode.STREAMING) {
                // L2 §6.2.4 STREAMING: ACCEPTED + STREAM_READY (cursor=streamRef).
                produceResponse(AgentBusEventType.INVOCATION_STREAM_READY,
                        "taskId=" + taskId + ";streamRef=stream://" + taskId, corrId,
                        routing(reqTenant, reqSource, req));
                return;
            }
            produceResponse(AgentBusEventType.INVOCATION_RESPONSE, "taskId=" + taskId + ";status=snapshot",
                    corrId, routing(reqTenant, reqSource, req));
            produceResponse(AgentBusEventType.INVOCATION_TERMINAL, "taskId=" + taskId + ";status=completed",
                    corrId, routing(reqTenant, reqSource, req));
        }

        /**
         * Build + send a single response message to {@code resp_out} mirroring
         * {@link RocketMqBrokerForwardingRelay#buildMessage} user-properties. Package-private so
         * UC-6 (per-eventType classify) + D9 can inject a chosen eventType directly; {@code source=RUNTIME},
         * {@code target=GATEWAY} (the response swap).
         *
         * @param eventType     the response event type to produce
         * @param respInline    the response inlinePayload (A2A response content: taskId / status / streamRef)
         * @param correlationId the correlation id stamped on the message (matches the request)
         * @param routing       the tenant/source/target routing triple stamped on the message
         */
        void produceResponse(AgentBusEventType eventType, String respInline, String correlationId,
                             ResponseRouting routing) {
            String messageId = "resp-" + respSeq.incrementAndGet();
            Message msg = new Message(TOPIC_INVOCATION_RESP_OUT, /* tags */ null, messageId,
                    ("target=" + routing.target()).getBytes(StandardCharsets.UTF_8));
            msg.putUserProperty("tenantId", routing.tenantId());
            msg.putUserProperty("messageId", messageId);
            msg.putUserProperty("sourceServiceId", routing.source());
            msg.putUserProperty("targetServiceId", routing.target());
            msg.putUserProperty("correlationId", correlationId);
            msg.putUserProperty("eventType", eventType.name());
            // P-06: control plane first-class user properties (the response relay's governance requires them)
            msg.putUserProperty("traceId", routing.traceId());
            msg.putUserProperty("idempotencyKey", routing.idempotencyKey());
            msg.putUserProperty("routeHandle", routing.routeHandle());
            msg.putUserProperty("capability", routing.capability());
            // P-06: response content (taskId/status/streamRef) rides inlinePayload (A2A response envelope
            // as DATA), NOT a control-descriptor payloadRef. The gateway reads it via responseToken.
            msg.putUserProperty("inlinePayload", respInline);
            try {
                producer.send(msg);
            } catch (RemotingException | MQBrokerException | MQClientException | InterruptedException e) {
                throw new IllegalStateException("temp runtime failed to produce response " + eventType, e);
            }
        }

        /**
         * UC-6 / UC-8 / UC-9 / D9 direct-injection overload: response-swap defaults
         * (source=RUNTIME, target=GATEWAY, tenant=TENANT).
         *
         * @param eventType     the response event type to produce
         * @param respInline    the response inlinePayload (A2A response content: taskId / status / streamRef)
         * @param correlationId the correlation id stamped on the message (matches the request)
         */
        void produceResponse(AgentBusEventType eventType, String respInline, String correlationId) {
            produceResponse(eventType, respInline, correlationId, ResponseRouting.defaults());
        }
    }
}
