/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingEnvelope;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingInboxPort;
import com.openjiuwen.bus.forwarding.spi.ForwardingMessageId;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.ForwardingStatus;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingRelayPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;
import com.openjiuwen.bus.forwarding.test.InMemoryForwardingOutbox;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Unit tests for {@link EventBusRelayWorker} — the consume→govern→re-produce relay.
 *
 * <p>Inline fakes for consumer / inbox / relay (deterministic, no broker self-consume
 * concern) + {@link InMemoryForwardingOutbox} as the real outbox+claim double (battle-
 * tested by the C3 forwarding contract tests). The worker is routing-agnostic, so the
 * two-hop topic routing (req vs deliver) is not exercised here — that lands with the
 * real 8-topic broker in the two-hop IT (slice E).
 *
 * <p>Covers the between-hops governance acts (P-06): skip-non-request (eventType
 * discriminator), control-plane presence (reject poison with absent control), inbox
 * dedup, plus the transient produce-failure path.
 */
class EventBusRelayWorkerTest {
    static final class FakeConsumer implements BrokerForwardingConsumerPort {
        final Deque<BrokerInboundMessage> queue = new ArrayDeque<>();
        final List<BrokerInboundMessage> committed = new ArrayList<>();
        final List<Map.Entry<BrokerInboundMessage, ForwardingFailureCode>> rejected = new ArrayList<>();

        @Override
        public void subscribe(String consumerServiceId, AgentBusEventType eventType,
                DeliveryFilter filter) {
            // no-op: inline fake does not track subscriptions
        }

        @Override
        public Optional<BrokerInboundMessage> poll(long nowMillisEpoch) {
            return Optional.ofNullable(queue.poll());
        }

        @Override
        public void commit(BrokerInboundMessage message) {
            committed.add(message);
        }

        @Override
        public void reject(BrokerInboundMessage message, ForwardingFailureCode code) {
            rejected.add(Map.entry(message, code));
        }

        @Override
        public void close() {
            // no-op: inline fake holds no external resources
        }

        @Override
        public boolean supportsBrokerSidePropertyFilter() {
            return true;
        }
    }

    static final class FakeInbox implements ForwardingInboxPort {
        final Set<String> seen = new HashSet<>();      // tenant|messageId|consumer
        final List<ForwardingMessageId> consumed = new ArrayList<>();
        final List<ForwardingMessageId> rejected = new ArrayList<>();

        @Override
        public ForwardingStatus.Inbox receive(ForwardingEnvelope env, String consumerServiceId,
                long nowMillisEpoch) {
            String key = env.tenantId() + "|" + env.messageId().value() + "|" + consumerServiceId;
            return seen.add(key) ? ForwardingStatus.Inbox.RECEIVED
                    : ForwardingStatus.Inbox.DUPLICATE_SUPPRESSED;
        }

        @Override
        public ForwardingStatus.Inbox markConsumed(ForwardingMessageId id, String tenantId,
                String consumerServiceId) {
            consumed.add(id);
            return ForwardingStatus.Inbox.CONSUMED;
        }

        @Override
        public ForwardingStatus.Inbox markRejected(ForwardingMessageId id, String tenantId,
                String consumerServiceId, ForwardingFailureCode code) {
            rejected.add(id);
            return ForwardingStatus.Inbox.REJECTED;
        }

        @Override
        public ForwardingStatus.Inbox statusOf(ForwardingMessageId id, String tenantId,
                String consumerServiceId) {
            return ForwardingStatus.Inbox.RECEIVED;
        }
    }

    static final class FakeRelay implements BrokerForwardingRelayPort {
        final List<ForwardingOutboxRecord> produced = new ArrayList<>();
        BrokerProduceOutcome outcome = BrokerProduceOutcome.accepted();

        @Override
        public BrokerProduceOutcome produce(ForwardingOutboxRecord record, long nowMillisEpoch) {
            produced.add(record);
            return outcome;
        }
    }

    private static BrokerInboundMessage hop1Request(String tenant, String messageId, String corrId,
                                                   String route, String target) {
        // P-06: the control plane rides FIRST-CLASS fields (no descriptor token in payloadRef).
        // payloadRef is the A2A data reference; control fields are populated for a valid relayable request.
        return new BrokerInboundMessage(tenant, messageId, "gateway-1", target, "event-bus",
                "ref://payload/" + messageId, corrId, AgentBusEventType.CLIENT_INVOCATION_REQUESTED,
                "trace-" + messageId, "idem-" + messageId, route, "a2a", Long.MAX_VALUE, null, null);
    }

    private static EventBusRelayWorker newWorker(FakeConsumer c, FakeInbox inbox, InMemoryForwardingOutbox outbox,
                                                  FakeRelay relay) {
        return new EventBusRelayWorker(c, inbox, outbox, outbox, relay, "event-bus", "event-bus", 60_000L,
                EventBusRelayWorker.FORWARD_REQUEST_TYPES);
    }

    /**
     * Response-relay worker (RESPONSE_TYPES, consumerServiceId "event-bus-resp" — distinct dedup key).
     *
     * @param c the inline fake consumer driving this response relay
     * @param inbox the inline fake inbox for dedup
     * @param outbox the real in-memory outbox + claim double
     * @param relay the inline fake relay that records produced records
     * @return a response-relay worker wired with the given fakes
     */
    private static EventBusRelayWorker newResponseWorker(FakeConsumer c, FakeInbox inbox,
                                                          InMemoryForwardingOutbox outbox, FakeRelay relay) {
        return new EventBusRelayWorker(c, inbox, outbox, outbox, relay, "event-bus-resp", "event-bus", 60_000L,
                EventBusRelayWorker.RESPONSE_TYPES);
    }

    /**
     * A resp_in response message: first-class control plane + response content (taskId/status) in
     * inlinePayload (P-06: A2A response envelope as DATA; the gateway reads it via responseToken).
     *
     * @param tenant the tenant scope
     * @param messageId the inbound message id
     * @param corrId the correlation id (first-class, mirrors the request)
     * @param eventType the response event type
     * @param responseTokens the taskId/status content carried in inlinePayload
     * @return a resp_in inbound message carrying first-class control + response content
     */
    private static BrokerInboundMessage respInMessage(String tenant, String messageId, String corrId,
                                                       AgentBusEventType eventType, String responseTokens) {
        return new BrokerInboundMessage(tenant, messageId, "runtime-1", "gateway-1", "event-bus-resp",
                null, corrId, eventType,
                "trace-" + messageId, "idem-" + messageId, "invocation", "a2a", Long.MAX_VALUE,
                responseTokens, null);
    }

    @Test
    void relaysHop1ToHop2AndCommits() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        EventBusRelayWorker w = newWorker(c, inbox, outbox, relay);

        c.queue.add(hop1Request("t1", "m1", "c1", "route-1", "runtime-1"));

        EventBusRelayWorker.RelayTickResult r = w.runOnce("t1", 100L, 5);

        assertEquals(1, r.relayed());
        assertEquals(0, r.dedupSuppressed());
        assertEquals(0, r.governanceRejected());
        assertEquals(1, relay.produced.size());     // hop2 re-published
        assertEquals(1, c.committed.size());        // hop1 committed (model B ack)
        assertEquals(0, c.rejected.size());
        assertEquals(1, inbox.consumed.size());     // audit: inbox CONSUMED
    }

    @Test
    void dedupSuppressesReplayedHop1WithoutDoubleProducing() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        EventBusRelayWorker w = newWorker(c, inbox, outbox, relay);

        c.queue.add(hop1Request("t1", "m1", "c1", "route-1", "runtime-1"));
        w.runOnce("t1", 100L, 5);                    // first relay → 1 hop2

        // redeliver the SAME hop1 (at-least-once): inbox dedup must suppress, no second hop2.
        c.queue.add(hop1Request("t1", "m1", "c1", "route-1", "runtime-1"));
        EventBusRelayWorker.RelayTickResult r2 = w.runOnce("t1", 200L, 5);

        assertEquals(0, r2.relayed());
        assertEquals(1, r2.dedupSuppressed());
        assertEquals(1, relay.produced.size());      // still only one hop2 produce
        assertEquals(2, c.committed.size());          // both deliveries committed (no redeliver)
    }

    @Test
    void rejectsControlPlaneIncompletePoison() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        EventBusRelayWorker w = newWorker(c, inbox, outbox, relay);

        // P-06: a relayable request whose control plane is absent (traceId/idempotencyKey/routeHandle/
        // capability null — back-compat 8-arg form) is poison — governance rejects without redelivery (a
        // broker redelivery cannot fix a missing control field). Replaces the pre-P-06 corrId-mismatch
        // poison: there is now a single first-class correlationId, so no header/descriptor mismatch exists.
        c.queue.add(new BrokerInboundMessage("t1", "m1", "gateway-1", "runtime-1", "event-bus",
                "ref://x", "c1", AgentBusEventType.CLIENT_INVOCATION_REQUESTED));

        EventBusRelayWorker.RelayTickResult r = w.runOnce("t1", 100L, 5);

        assertEquals(0, r.relayed());
        assertEquals(1, r.governanceRejected());
        assertEquals(0, relay.produced.size());      // no hop2 produced
        assertEquals(1, c.committed.size());         // poison committed (no redeliver loop)
        assertEquals(1, inbox.rejected.size());      // audit: inbox REJECTED
    }

    @Test
    void skipsNonRequestEcho() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        EventBusRelayWorker w = newWorker(c, inbox, outbox, relay);

        // a self-produced response echo (no eventType= token) — commit + advance, do not relay.
        c.queue.add(new BrokerInboundMessage("t1", "m1", "event-bus", "gateway-1", "event-bus",
                "taskId=task-1;status=completed", "c1", null));

        EventBusRelayWorker.RelayTickResult r = w.runOnce("t1", 100L, 5);

        assertEquals(0, r.relayed());
        assertEquals(1, r.skipped());
        assertEquals(1, c.committed.size());
        assertEquals(0, relay.produced.size());
    }

    @Test
    void produceUnavailableRejectsForRedelivery() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        relay.outcome = BrokerProduceOutcome.unavailable(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
        EventBusRelayWorker w = newWorker(c, inbox, outbox, relay);

        c.queue.add(hop1Request("t1", "m1", "c1", "route-1", "runtime-1"));

        EventBusRelayWorker.RelayTickResult r = w.runOnce("t1", 100L, 5);

        assertEquals(0, r.relayed());               // produce failed → not relayed
        assertEquals(1, relay.produced.size());      // produce was attempted
        assertEquals(0, c.committed.size());         // hop1 NOT committed
        assertEquals(1, c.rejected.size());           // hop1 rejected → broker redelivers
        assertEquals(ForwardingFailureCode.RECEIVER_UNAVAILABLE, c.rejected.get(0).getValue());
        assertEquals(0, inbox.consumed.size());      // not CONSUMED (not terminal)
    }

    @Test
    void responseRelayRelaysRespInToRespOutAndCommits() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        EventBusRelayWorker w = newResponseWorker(c, inbox, outbox, relay);

        c.queue.add(respInMessage("t1", "m1", "c1", AgentBusEventType.INVOCATION_RESPONSE,
                "taskId=task-1;status=snapshot"));

        EventBusRelayWorker.RelayTickResult r = w.runOnce("t1", 100L, 5);

        assertEquals(1, r.relayed());
        assertEquals(0, r.dedupSuppressed());
        assertEquals(0, r.governanceRejected());
        assertEquals(1, relay.produced.size());     // resp_out re-published
        assertEquals(1, c.committed.size());        // resp_in committed (model B ack)
        assertEquals(0, c.rejected.size());
        assertEquals(1, inbox.consumed.size());     // audit: inbox CONSUMED
    }

    @Test
    void responseRelayDedupSuppressesReplayedRespInWithoutDoubleProducing() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        EventBusRelayWorker w = newResponseWorker(c, inbox, outbox, relay);

        c.queue.add(respInMessage("t1", "m1", "c1", AgentBusEventType.INVOCATION_RESPONSE,
                "taskId=task-1;status=snapshot"));
        w.runOnce("t1", 100L, 5);                    // first relay → 1 resp_out

        // redeliver the SAME resp_in (at-least-once): inbox dedup suppresses, no second resp_out.
        c.queue.add(respInMessage("t1", "m1", "c1", AgentBusEventType.INVOCATION_RESPONSE,
                "taskId=task-1;status=snapshot"));
        EventBusRelayWorker.RelayTickResult r2 = w.runOnce("t1", 200L, 5);

        assertEquals(0, r2.relayed());
        assertEquals(1, r2.dedupSuppressed());
        assertEquals(1, relay.produced.size());      // still only one resp_out produce
        assertEquals(2, c.committed.size());          // both deliveries committed (no redeliver)
    }

    @Test
    void responseRelaySkipsOutOfDirectionRequestEcho() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        EventBusRelayWorker w = newResponseWorker(c, inbox, outbox, relay);

        // a request eventType landing on resp_in (out-of-direction) — commit + advance, do not relay.
        c.queue.add(hop1Request("t1", "m1", "c1", "route-1", "runtime-1"));

        EventBusRelayWorker.RelayTickResult r = w.runOnce("t1", 100L, 5);

        assertEquals(0, r.relayed());
        assertEquals(1, r.skipped());
        assertEquals(1, c.committed.size());
        assertEquals(0, relay.produced.size());
    }

    @Test
    void responseRelayRejectsControlPlaneIncompletePoison() {
        FakeConsumer c = new FakeConsumer();
        FakeInbox inbox = new FakeInbox();
        InMemoryForwardingOutbox outbox = new InMemoryForwardingOutbox();
        FakeRelay relay = new FakeRelay();
        EventBusRelayWorker w = newResponseWorker(c, inbox, outbox, relay);

        // P-06: a relayable response whose control plane is absent is poison — governance rejects.
        // (Replaces the pre-P-06 corrId-mismatch poison; single first-class correlationId → no mismatch.)
        c.queue.add(new BrokerInboundMessage("t1", "m1", "runtime-1", "gateway-1", "event-bus-resp",
                "taskId=t1;status=snapshot", "c1", AgentBusEventType.INVOCATION_RESPONSE));

        EventBusRelayWorker.RelayTickResult r = w.runOnce("t1", 100L, 5);

        assertEquals(0, r.relayed());
        assertEquals(1, r.governanceRejected());
        assertEquals(0, relay.produced.size());      // no resp_out produced
        assertEquals(1, c.committed.size());         // poison committed (no redeliver loop)
        assertEquals(1, inbox.rejected.size());      // audit: inbox REJECTED
    }
}
