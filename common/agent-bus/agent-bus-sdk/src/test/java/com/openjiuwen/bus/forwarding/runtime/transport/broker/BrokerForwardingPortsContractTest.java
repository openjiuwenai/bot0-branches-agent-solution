/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.bus.forwarding.runtime.transport.BrokerTopicResolver;
import com.openjiuwen.bus.forwarding.runtime.transport.DefaultBrokerTopicResolver;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingRouteHandle;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import org.junit.jupiter.api.Test;

import java.util.Optional;

/**
 * Contract test for the broker SPI scaffold (Stage 26 + decision packet
 * agent-bus-broker-filtering-spi-completion §3) — verifies the T4 hybrid
 * governance invariants hold on the {@link InMemoryBroker} double + its
 * per-consumer {@link InMemoryBroker#consumerFor consumer}, the same way the
 * outbox contract test pins the JDBC adapter contract on
 * {@code InMemoryForwardingOutbox}.
 *
 * <p>Produce side: payload reference rides in the header never the body (②),
 * the topic is derived from the record's eventType via the injected
 * {@link BrokerTopicResolver} (Option B — the opaque routeHandle is never read
 * for the topic), a record with no eventType → non-retryable ROUTE_NOT_FOUND,
 * broker-unavailable → retryable.
 *
 * <p>Consume side (§3 "after" SPI): a consumer subscribes once at startup with a
 * {@link DeliveryFilter} (named-property criteria — D3; the broker-agnostic
 * surface the RocketMQ adapter will translate to {@code MessageSelector.bySql}),
 * then {@code poll(nowMillisEpoch)} (param-less — D4) returns the next message
 * whose tenant + target-serviceId match the subscribed filter; commit advances
 * the consumer-group offset (model B ack-after-consume), reject redelivers
 * (at-least-once). Consumer-group isolation (L3) holds across separate
 * {@code consumerFor} instances backed by the same shared broker.
 */
class BrokerForwardingPortsContractTest {
    @Test
    void produce_data_bearing_carries_payload_ref_in_header_not_body() {
        InMemoryBroker broker = broker();
        ForwardingOutboxRecord record = record("tenant-a", "msg-1", "ref-secret-payload");

        BrokerProduceOutcome outcome = broker.produce(record, 1_000L);

        assertThat(outcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ACCEPTED);
        BrokerOutboundMessage stored = broker.outboundMessage("tenant-a", "msg-1").orElse(null);
        assertThat(stored).isNotNull();
        // payload reference rides in the header (conditionally present for data-bearing)...
        assertThat(stored.headers().payloadRef()).isEqualTo("ref-secret-payload");
        assertThat(stored.headers().carriesPayloadRef()).isTrue();
        // ...and NEVER in the body (the body is a routing descriptor only — §6.2②).
        assertThat(stored.routingDescriptor()).doesNotContain("ref-secret-payload");
    }

    @Test
    void produce_control_only_omits_payload_ref_header() {
        InMemoryBroker broker = broker();
        ForwardingOutboxRecord record = record("tenant-a", "msg-1", null);

        broker.produce(record, 1_000L);

        BrokerOutboundMessage stored = broker.outboundMessage("tenant-a", "msg-1").orElse(null);
        assertThat(stored.headers().payloadRef()).isNull();
        assertThat(stored.headers().carriesPayloadRef()).isFalse();
    }

    @Test
    void produce_derives_topic_from_event_type_via_resolver() {
        // The resolver is the sanctioned seam; the broker must publish to the topic the resolver
        // returns for the record's eventType, not by unwrapping routeHandle.value() itself.
        InMemoryBroker broker = broker((et, suffix) -> "topic-from-resolver");
        ForwardingOutboxRecord record = record("tenant-a", "msg-1", null);

        broker.produce(record, 1_000L);

        assertThat(broker.messageCount("topic-from-resolver")).isEqualTo(1);
    }

    @Test
    void produce_null_event_type_returns_non_retryable_route_not_found() {
        // A record with no eventType cannot yield a topic (Option B: topic is eventType-driven) →
        // non-retryable ROUTE_NOT_FOUND (a JDBC-loaded back-compat row without the V3 column).
        InMemoryBroker broker = broker();
        ForwardingOutboxRecord record = recordNoEventType("tenant-a", "msg-1", null);

        BrokerProduceOutcome outcome = broker.produce(record, 1_000L);

        assertThat(outcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.ROUTE_NOT_FOUND);
        assertThat(outcome.failureCode()).isEqualTo(ForwardingFailureCode.ROUTE_NOT_FOUND);
        assertThat(outcome.failureCode().nonRetryable()).isTrue();
    }

    @Test
    void produce_when_broker_unavailable_returns_retryable_unavailable() {
        InMemoryBroker broker = broker();
        broker.setUnavailable(true);
        ForwardingOutboxRecord record = record("tenant-a", "msg-1", null);

        BrokerProduceOutcome outcome = broker.produce(record, 1_000L);

        assertThat(outcome.outcome()).isEqualTo(BrokerProduceOutcome.Outcome.UNAVAILABLE);
        assertThat(outcome.failureCode()).isEqualTo(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
        assertThat(outcome.failureCode().retryable()).isTrue();
    }

    @Test
    void subscribe_then_poll_returns_next_message_matching_the_filter() {
        InMemoryBroker broker = broker();
        broker.produce(record("tenant-a", "msg-1", "ref-1"), 1_000L);

        // the consumer IS the target runtime (serviceId = the message's targetServiceId); it
        // subscribes with forRuntime(tenant, myServiceId) — D2/D10: receive only what is targeted at it.
        BrokerForwardingConsumerPort consumer = broker.consumerFor("consumer-a");
        consumer.subscribe("consumer-a", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-a", "target-svc"));

        BrokerInboundMessage m = consumer.poll(2_000L).orElseThrow();

        assertThat(m.messageId()).isEqualTo("msg-1");
        assertThat(m.tenantId()).isEqualTo("tenant-a");
        assertThat(m.sourceServiceId()).isEqualTo("source-svc");
        assertThat(m.targetServiceId()).isEqualTo("target-svc");
        assertThat(m.consumerServiceId()).isEqualTo("consumer-a");
        assertThat(m.payloadRef()).isEqualTo("ref-1");
    }

    @Test
    void poll_filters_out_cross_tenant_messages() {
        InMemoryBroker broker = broker((et, suffix) -> "shared-topic");
        broker.produce(record("tenant-a", "msg-1", null), 1_000L);

        // a runtime in tenant-b never receives tenant-a's message — the filter (tenantId) excludes it.
        BrokerForwardingConsumerPort consumer = broker.consumerFor("consumer-b");
        consumer.subscribe("consumer-b", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-b", "target-svc"));

        assertThat(consumer.poll(2_000L)).isEmpty();
    }

    @Test
    void poll_filters_out_messages_targeted_at_a_different_service() {
        InMemoryBroker broker = broker();
        broker.produce(record("tenant-a", "msg-1", null), 1_000L); // targetServiceId = "target-svc"

        // this consumer is "other-svc" — msg-1 is targeted at "target-svc", so it is filtered out.
        BrokerForwardingConsumerPort consumer = broker.consumerFor("consumer-a");
        consumer.subscribe("consumer-a", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-a", "other-svc"));

        assertThat(consumer.poll(2_000L)).isEmpty();
    }

    @Test
    void commit_advances_offset_so_message_is_not_redelivered() {
        InMemoryBroker broker = broker();
        broker.produce(record("tenant-a", "msg-1", null), 1_000L);
        BrokerForwardingConsumerPort consumer = broker.consumerFor("consumer-a");
        consumer.subscribe("consumer-a", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-a", "target-svc"));

        BrokerInboundMessage m = consumer.poll(2_000L).orElseThrow();
        consumer.commit(m);

        assertThat(consumer.poll(3_000L)).isEmpty();
    }

    @Test
    void poll_without_commit_redelivers_the_same_message_at_least_once() {
        InMemoryBroker broker = broker();
        broker.produce(record("tenant-a", "msg-1", null), 1_000L);
        BrokerForwardingConsumerPort consumer = broker.consumerFor("consumer-a");
        consumer.subscribe("consumer-a", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-a", "target-svc"));

        BrokerInboundMessage first = consumer.poll(2_000L).orElseThrow();
        // no commit, no reject — broker must redeliver
        BrokerInboundMessage second = consumer.poll(3_000L).orElseThrow();

        assertThat(second.messageId()).isEqualTo(first.messageId());
        assertThat(second.tenantId()).isEqualTo(first.tenantId());
    }

    @Test
    void reject_does_not_commit_and_records_code_for_observability() {
        InMemoryBroker broker = broker();
        broker.produce(record("tenant-a", "msg-1", null), 1_000L);
        BrokerForwardingConsumerPort consumer = broker.consumerFor("consumer-a");
        consumer.subscribe("consumer-a", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-a", "target-svc"));

        BrokerInboundMessage m = consumer.poll(2_000L).orElseThrow();
        consumer.reject(m, ForwardingFailureCode.TENANT_MISMATCH);

        // reject does not advance the offset → redelivered
        assertThat(consumer.poll(3_000L)).isPresent();
        assertThat(broker.lastRejectCode("msg-1")).isEqualTo(ForwardingFailureCode.TENANT_MISMATCH);
    }

    @Test
    void consumer_groups_maintain_independent_offsets() {
        InMemoryBroker broker = broker();
        broker.produce(record("tenant-a", "msg-1", null), 1_000L);

        // consumer-a commits; consumer-b (a different consumer-group, separate instance backed by
        // the same shared broker) still sees the message — independent offsets per consumerServiceId.
        BrokerForwardingConsumerPort consumerA = broker.consumerFor("consumer-a");
        consumerA.subscribe("consumer-a", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-a", "target-svc"));
        BrokerInboundMessage forA = consumerA.poll(2_000L).orElseThrow();
        consumerA.commit(forA);

        BrokerForwardingConsumerPort consumerB = broker.consumerFor("consumer-b");
        consumerB.subscribe("consumer-b", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-a", "target-svc"));
        Optional<BrokerInboundMessage> forB = consumerB.poll(3_000L);

        assertThat(forB).isPresent();
        assertThat(forB.orElseThrow().messageId()).isEqualTo("msg-1");
        assertThat(forB.orElseThrow().consumerServiceId()).isEqualTo("consumer-b");
    }

    @Test
    void in_memory_consumer_supports_broker_side_property_filter() {
        // the in-memory "broker" filters by the subscribed properties itself — the broker-side
        // equivalent — so the capability bit is true (D8). (A client-side-only fallback would be false.)
        InMemoryBroker broker = broker();
        assertThat(broker.consumerFor("consumer-a").supportsBrokerSidePropertyFilter()).isTrue();
    }

    @Test
    void close_is_idempotent_and_does_not_throw() {
        InMemoryBroker broker = broker();
        BrokerForwardingConsumerPort consumer = broker.consumerFor("consumer-a");
        consumer.close();
        consumer.close();
    }

    @Test
    void produce_outcome_accepted_must_not_carry_a_failure_code() {
        assertThatThrownBy(() -> new BrokerProduceOutcome(BrokerProduceOutcome.Outcome.ACCEPTED,
                ForwardingFailureCode.ROUTE_NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(BrokerProduceOutcome.accepted().failureCode()).isNull();
    }

    @Test
    void produce_outcome_unavailable_requires_a_retryable_code() {
        assertThatThrownBy(() -> BrokerProduceOutcome.unavailable(ForwardingFailureCode.ROUTE_NOT_FOUND))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrokerProduceOutcome.unavailable(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void produce_outcome_route_not_found_requires_a_non_retryable_code() {
        assertThatThrownBy(() -> BrokerProduceOutcome.routeNotFound(ForwardingFailureCode.RECEIVER_UNAVAILABLE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrokerProduceOutcome.routeNotFound(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void message_headers_require_mandatory_routing_metadata() {
        assertThatThrownBy(() -> new BrokerMessageHeaders(null, "m", "s", "t", null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BrokerMessageHeaders(" ", "m", "s", "t", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BrokerMessageHeaders("tenant", "m", "s", "t", " ", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // FEAT-013: correlationId is nullable but rejects a blank value.
        assertThatThrownBy(() -> new BrokerMessageHeaders("tenant", "m", "s", "t", null, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    void inbound_message_requires_consumer_service_id_and_metadata() {
        assertThatThrownBy(() -> new BrokerInboundMessage("tenant", "m", "s", "t", null, null, null, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new BrokerInboundMessage("tenant", "m", "s", "t", " ", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        // FEAT-013: correlationId is nullable but rejects a blank value.
        assertThatThrownBy(() -> new BrokerInboundMessage("tenant", "m", "s", "t", "c", null, " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("correlationId");
    }

    @Test
    void produce_then_poll_propagates_corr_id_to_headers_to_inbound() {
        InMemoryBroker broker = broker();
        // record() fixture mirrors correlationId="corr-"+messageId from the envelope; produce builds
        // headers from the record; poll mirrors headers→inbound. The gateway (S2) reads inbound.correlationId().
        broker.produce(record("tenant-a", "msg-corr", "ref-1"), 1_000L);

        BrokerForwardingConsumerPort consumer = broker.consumerFor("consumer-a");
        consumer.subscribe("consumer-a", AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                DeliveryFilter.forRuntime("tenant-a", "target-svc"));
        BrokerInboundMessage m = consumer.poll(2_000L).orElseThrow();

        assertThat(m.correlationId()).isEqualTo("corr-msg-corr");
        assertThat(m.eventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
        // and the stored outbound carries the same correlationId + eventType in its headers
        assertThat(broker.outboundMessage("tenant-a", "msg-corr").orElseThrow().headers().correlationId())
                .isEqualTo("corr-msg-corr");
        assertThat(broker.outboundMessage("tenant-a", "msg-corr").orElseThrow().headers().eventType())
                .isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
    }

    @Test
    void produce_control_only_carries_correlation_id_in_header() {
        // a CONTROL_ONLY record (payloadRef=null) still carries its correlationId — correlation is
        // routing metadata, independent of the payloadRef data-reference path (§6.2 ②).
        InMemoryBroker broker = broker();
        broker.produce(record("tenant-a", "msg-corr-ctrl", null), 1_000L);

        BrokerOutboundMessage stored = broker.outboundMessage("tenant-a", "msg-corr-ctrl").orElse(null);
        assertThat(stored.headers().correlationId()).isEqualTo("corr-msg-corr-ctrl");
        assertThat(stored.headers().eventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
    }

    private InMemoryBroker broker() {
        return new InMemoryBroker(new DefaultBrokerTopicResolver(), "req");
    }

    private InMemoryBroker broker(BrokerTopicResolver resolver) {
        return new InMemoryBroker(resolver, "req");
    }

    private static ForwardingRouteHandle routeHandle(String tenantId) {
        return new ForwardingRouteHandle("route-for-" + tenantId, tenantId);
    }

    private ForwardingOutboxRecord record(String tenantId, String messageId, String payloadRef) {
        return record(tenantId, messageId, payloadRef, AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
    }

    private ForwardingOutboxRecord recordNoEventType(String tenantId, String messageId, String payloadRef) {
        return record(tenantId, messageId, payloadRef, null);
    }

    private ForwardingOutboxRecord record(String tenantId, String messageId, String payloadRef,
                                          AgentBusEventType eventType) {
        return new ForwardingOutboxRecord(
                tenantId,
                new ForwardingMessageId(messageId),
                "source-svc",
                "target-svc",
                routeHandle(tenantId),
                payloadRef,
                ForwardingStatus.Outbox.PENDING,
                0,
                0L,
                0L,
                0L,
                null,
                null,
                "corr-" + messageId, // correlationId (non-null for propagation test)
                eventType); // eventType (null → ROUTE_NOT_FOUND; non-null → derived topic)
    }
}
