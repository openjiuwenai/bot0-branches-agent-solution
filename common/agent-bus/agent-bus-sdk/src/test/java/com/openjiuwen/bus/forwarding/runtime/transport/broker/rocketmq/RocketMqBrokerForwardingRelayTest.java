/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker.rocketmq;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.runtime.transport.BrokerTopicResolver;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;

import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link RocketMqBrokerForwardingRelay} (FEAT-013/014, S3).
 *
 * <p>Pins the pure record&rarr;{@link Message} mapping (decision §6.2 ② routing-descriptor
 * body; routing-metadata user properties incl. {@code correlationId} per FEAT-013 §4.2 so
 * the gateway can match responses) and the {@code produce} outcome mapping
 * (routeNotFound / accepted / unavailable) via a fake
 * {@link RocketMqBrokerForwardingRelay.MessageSender} — no live broker (the real
 * {@code DefaultMQProducer} round-trip is the S7 {@code @EnabledIfEnvironmentProperty}
 * integration test, env-guarded).
 *
 * <p>Authority: {@code architecture/L2-Low-Level-Design/agent-bus/
 * feat-013-client-invocation-event-forwarding.md} §4.1 / §5.2 / §8.1.
 */
class RocketMqBrokerForwardingRelayTest {
    private static final String TENANT = "tenant-a";
    private static final String ROUTE = "route-for-tenant-a";
    private static final String SOURCE = "source-svc";
    private static final String TARGET = "target-svc";
    private static final String SUFFIX = "req";

    @Test
    void buildMessage_maps_record_to_descriptor_body_and_metadata() {
        ForwardingOutboxRecord record = record("msg-1", "payload-ref-1", "corr-1");

        Message msg = RocketMqBrokerForwardingRelay.buildMessage(record, "topic-from-resolver");

        assertThat(msg.getTopic()).isEqualTo("topic-from-resolver");
        // §6.2 ②: body is a routing descriptor only — NEVER the payload body.
        assertThat(new String(msg.getBody(), StandardCharsets.UTF_8)).isEqualTo("target=" + TARGET);
        // keys = messageId (broker-side query / dedup hook)
        assertThat(msg.getKeys()).isEqualTo("msg-1");
        // routing metadata rides as user properties
        assertThat(msg.getUserProperty("tenantId")).isEqualTo(TENANT);
        assertThat(msg.getUserProperty("messageId")).isEqualTo("msg-1");
        assertThat(msg.getUserProperty("sourceServiceId")).isEqualTo(SOURCE);
        assertThat(msg.getUserProperty("targetServiceId")).isEqualTo(TARGET);
        // FEAT-013 §4.2: correlationId rides as a user property (gateway matches responses by it)
        assertThat(msg.getUserProperty("correlationId")).isEqualTo("corr-1");
        assertThat(msg.getUserProperty("eventType")).isEqualTo("CLIENT_INVOCATION_REQUESTED");
        assertThat(msg.getUserProperty("payloadRef")).isEqualTo("payload-ref-1");
    }

    @Test
    void buildMessage_omits_corr_id_payload_ref_when_control_only() {
        ForwardingOutboxRecord record = record("msg-ctrl", null, null);

        Message msg = RocketMqBrokerForwardingRelay.buildMessage(record, "topic-x");

        assertThat(msg.getUserProperty("correlationId")).isNull();
        assertThat(msg.getUserProperty("payloadRef")).isNull();
        // mandatory routing metadata still present
        assertThat(msg.getUserProperty("tenantId")).isEqualTo(TENANT);
        assertThat(msg.getUserProperty("messageId")).isEqualTo("msg-ctrl");
    }

    @Test
    void buildMessage_body_does_not_leak_payload_ref() {
        // §6.2 ②: the payload reference rides as a header, never in the body.
        ForwardingOutboxRecord record = record("msg-1", "ref-secret-payload", "corr-1");
        Message msg = RocketMqBrokerForwardingRelay.buildMessage(record, "topic-x");
        assertThat(new String(msg.getBody(), StandardCharsets.UTF_8)).doesNotContain("ref-secret-payload");
    }

    @Test
    void produce_returns_accepted_when_sender_succeeds() {
        List<Message> sent = new ArrayList<>();
        RocketMqBrokerForwardingRelay relay = new RocketMqBrokerForwardingRelay(
                resolver("topic-from-resolver"), SUFFIX, sent::add);

        BrokerProduceOutcome outcome = relay.produce(record("msg-1", "ref-1", "corr-1"), 1_000L);

        assertThat(outcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ACCEPTED);
        assertThat(outcome.failureCode()).isNull();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getTopic()).isEqualTo("topic-from-resolver");
        // the produced message carries the correlationId so the gateway can match the response
        assertThat(sent.get(0).getUserProperty("correlationId")).isEqualTo("corr-1");
    }

    @Test
    void produce_returns_route_not_found_when_record_has_no_event_type() {
        // Option B: the topic is derived from the record's eventType; a record with no eventType
        // (a JDBC-loaded back-compat row without the V3 column) cannot yield a topic → ROUTE_NOT_FOUND.
        RocketMqBrokerForwardingRelay relay = new RocketMqBrokerForwardingRelay(
                resolver("topic-x"), SUFFIX, msg -> {});

        BrokerProduceOutcome outcome = relay.produce(recordNoEventType("msg-1", null, null), 1_000L);

        assertThat(outcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ROUTE_NOT_FOUND);
        assertThat(outcome.failureCode()).isEqualTo(ForwardingFailureCode.ROUTE_NOT_FOUND);
        assertThat(outcome.failureCode().nonRetryable()).isTrue();
    }

    @Test
    void produce_returns_unavailable_when_sender_throws() {
        RocketMqBrokerForwardingRelay relay = new RocketMqBrokerForwardingRelay(
                resolver("topic-x"), SUFFIX, msg -> {
                    throw new IllegalStateException("broker down");
                });

        BrokerProduceOutcome outcome = relay.produce(record("msg-1", null, "corr-1"), 1_000L);

        assertThat(outcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.UNAVAILABLE);
        assertThat(outcome.failureCode()).isEqualTo(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
        assertThat(outcome.failureCode().retryable()).isTrue();
    }

    @Test
    void produce_returns_unavailable_on_InterruptedException() {
        RocketMqBrokerForwardingRelay relay = new RocketMqBrokerForwardingRelay(
                resolver("topic-x"), SUFFIX, msg -> {
                    throw new InterruptedException();
                });

        BrokerProduceOutcome outcome = relay.produce(record("msg-1", null, null), 1_000L);
        assertThat(outcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.UNAVAILABLE);
        assertThat(outcome.failureCode()).isEqualTo(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
        // G.CON.10: produce does NOT restore the interrupt flag — the dispatch loop observes
        // cancellation via its own cooperative flag, not via Thread.isInterrupted().
    }

    @Test
    void produce_rejects_null_record() {
        RocketMqBrokerForwardingRelay relay = new RocketMqBrokerForwardingRelay(
                resolver("topic-x"), SUFFIX, msg -> {});
        assertThatThrownBy(() -> relay.produce(null, 1_000L))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("record");
    }

    /** A resolver that always returns the given topic (ignores eventType / suffix) — for controlled-topic tests. */
    private static BrokerTopicResolver resolver(String topic) {
        return (et, suffix) -> topic;
    }

    private static ForwardingOutboxRecord record(String messageId, String payloadRef, String correlationId) {
        return record(messageId, payloadRef, correlationId, AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
    }

    private static ForwardingOutboxRecord recordNoEventType(String messageId, String payloadRef,
                                                             String correlationId) {
        return record(messageId, payloadRef, correlationId, null);
    }

    private static ForwardingOutboxRecord record(String messageId, String payloadRef, String correlationId,
                                                 AgentBusEventType eventType) {
        return new ForwardingOutboxRecord(
                TENANT,
                new ForwardingMessageId(messageId),
                SOURCE,
                TARGET,
                new ForwardingRouteHandle(ROUTE, TENANT),
                payloadRef,
                ForwardingStatus.Outbox.PENDING,
                0, 0L, 0L, 0L, null, null,
                correlationId,
                eventType);
    }
}
