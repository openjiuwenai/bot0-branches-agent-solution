/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.bus.forwarding.runtime.transport.broker;

import com.openjiuwen.bus.forwarding.runtime.transport.BrokerTopicResolver;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.bus.forwarding.spi.ForwardingFailureCode;
import com.openjiuwen.bus.forwarding.spi.ForwardingOutboxRecord;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingConsumerPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerForwardingRelayPort;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerInboundMessage;
import com.openjiuwen.bus.forwarding.spi.broker.BrokerProduceOutcome;
import com.openjiuwen.bus.forwarding.spi.broker.DeliveryFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory test double for the broker relay ({@link BrokerForwardingRelayPort}) +
 * the factory for per-consumer {@link BrokerForwardingConsumerPort} doubles —
 * NON-PRODUCTION.
 *
 * <p>Simulates broker semantics in plain JDK, mirroring how
 * {@code InMemoryForwardingOutbox} stands in for the JDBC outbox: the injected
 * {@link BrokerTopicResolver} derives the topic from each record's
 * {@link AgentBusEventType} (+ hop suffix — Option B, decoupled from the opaque
 * routeHandle), produce appends
 * a {@link BrokerOutboundMessage} to an in-memory queue (a single ordered
 * partition per topic; a global sequence orders cross-topic poll). Consumers are
 * obtained per consumer-group via {@link #consumerFor(String)} — each is a
 * {@link InMemoryBrokerConsumer} backed by this shared broker's queues; a
 * consumer subscribes with a {@link DeliveryFilter} and polls param-less,
 * receiving only messages whose properties match the subscribed filter (the
 * in-memory "broker" filters by the properties itself — the broker-side
 * equivalent, so {@link BrokerForwardingConsumerPort#supportsBrokerSidePropertyFilter}
 * is true). commit advances the consumer-group offset (model B ack-after-consume);
 * at-least-once redelivery is exercised by polling again without committing.
 *
 * <p><b>Per-consumer instances.</b> A single shared broker serves multiple
 * consumer-groups via separate {@code consumerFor} instances — that mirrors the
 * real adapter (one {@code DefaultLitePullConsumer} per consumer-group), which
 * the param-less {@code poll} requires: the consumer's group + filter were fixed
 * at subscribe time, so {@code poll} cannot direct across groups. Within one
 * consumer, {@code subscribe} may be called multiple times to accumulate
 * subscriptions across routes (multi-topic consume on one consumer-group, like
 * RocketMQ's multi-{@code subscribe}).
 *
 * <p>No broker client, no network, no scheduler — it is a deterministic harness
 * for the Stage 26 + §3 governance invariants (payloadRef-in-header, routeHandle
 * opaque, L2 tenant reject, consumer-group isolation, redelivery, filter
 * matching). A real RocketMQ adapter (decision §7 / Stage 28) is the production
 * target; this double deliberately stores the {@link BrokerOutboundMessage} it
 * builds so contract tests can introspect that the payload reference rides in the
 * header, never in the body.
 *
 * <p>Authority: {@code docs/architecture/l0/10-governance/review-packets/
 * agent-bus-broker-filtering-spi-completion-decision.md} (§3 after SPI +
 * Stage 25 §10 guardrails, Stage 26 broker SPI scaffold).
 *
 * @since 0.1.0
 */
// non-production — test fixture only; real broker adapter is decision §7 / Stage 28
public final class InMemoryBroker implements BrokerForwardingRelayPort {
    private final BrokerTopicResolver resolver;
    private final String suffix;

    // topic → append-only queue
    private final Map<String, List<QueueEntry>> queues = new LinkedHashMap<>();

    // consumerServiceId@topic → next read index
    private final Map<String, Integer> offsets = new LinkedHashMap<>();

    // tenantId|messageId → location
    private final Map<String, Location> locations = new LinkedHashMap<>();

    // messageId → last reject code (observability)
    private final Map<String, ForwardingFailureCode> rejections = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile boolean unavailable = false;

    public InMemoryBroker(BrokerTopicResolver resolver, String suffix) {
        this.resolver = Objects.requireNonNull(resolver, "resolver is required");
        this.suffix = requireSuffix(suffix);
    }

    /** A queued broker message; its index in the topic list is the consumer-group offset. */
    private static final class QueueEntry {
        final long sequence;              // global append order, for cross-topic poll ordering
        final BrokerOutboundMessage message;

        QueueEntry(long sequence, BrokerOutboundMessage message) {
            this.sequence = sequence;
            this.message = message;
        }
    }

    /** Locates a polled message for commit / reject without leaking topic/offset on the message. */
    private record Location(String topic, int index) {}

    /**
     * Test-only: force the broker into a transiently-unavailable state (simulates UNAVAILABLE produce).
     *
     * @param unavailable true to mark the broker unavailable, false to clear
     */
    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    /**
     * Test-only introspection: the stored outbound message (so contracts can assert header vs body).
     *
     * @param tenantId  the tenant scope to look up
     * @param messageId the message identifier to look up
     * @return the stored outbound message, or an empty {@link Optional} if no message was produced for the pair
     */
    public synchronized Optional<BrokerOutboundMessage> outboundMessage(String tenantId, String messageId) {
        requireNonBlank(tenantId, "tenantId");
        requireNonBlank(messageId, "messageId");
        for (List<QueueEntry> q : queues.values()) {
            for (QueueEntry e : q) {
                BrokerMessageHeaders h = e.message.headers();
                if (h.tenantId().equals(tenantId) && h.messageId().equals(messageId)) {
                    return Optional.of(e.message);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Test-only introspection: number of messages produced to a topic.
     *
     * @param topic the topic to count messages on
     * @return the number of queued messages on the topic (0 if the topic is unknown)
     */
    public synchronized int messageCount(String topic) {
        requireNonBlank(topic, "topic");
        List<QueueEntry> q = queues.get(topic);
        return q == null ? 0 : q.size();
    }

    /**
     * Test-only introspection: last reject code recorded for a message (null if none / not rejected).
     *
     * @param messageId the message identifier whose reject code to read
     * @return the last recorded reject code, or {@code null} if the message was never rejected
     */
    public synchronized ForwardingFailureCode lastRejectCode(String messageId) {
        requireNonBlank(messageId, "messageId");
        return rejections.get(messageId);
    }

    /**
     * Obtain a per-consumer {@link BrokerForwardingConsumerPort} double bound to the
     * given consumer-group, backed by this shared broker's queues. Each consumer
     * subscribes with its own {@link DeliveryFilter}; offsets are tracked per
     * {@code consumerServiceId} so distinct consumers are isolated (L3).
     *
     * @param consumerServiceId the broker consumer-group identifier
     * @return a per-consumer double backed by this broker's queues
     */
    public BrokerForwardingConsumerPort consumerFor(String consumerServiceId) {
        requireNonBlank(consumerServiceId, "consumerServiceId");
        return new InMemoryBrokerConsumer(this, consumerServiceId);
    }

    @Override
    public synchronized BrokerProduceOutcome produce(ForwardingOutboxRecord record, long nowMillisEpoch) {
        Objects.requireNonNull(record, "record is required");
        return produce(toOutboundMessage(record), nowMillisEpoch);
    }

    @Override
    public synchronized BrokerProduceOutcome produce(BrokerOutboundMessage message, long nowMillisEpoch) {
        Objects.requireNonNull(message, "message is required");
        // Option B: derive the topic from the message's eventType via the injected resolver
        // (event-type-driven; the opaque routeHandle is never read for the topic — HD4 preserved).
        // A null eventType cannot yield a topic → non-retryable ROUTE_NOT_FOUND.
        AgentBusEventType eventType = message.headers().eventType();
        if (eventType == null) {
            return BrokerProduceOutcome.routeNotFound(ForwardingFailureCode.ROUTE_NOT_FOUND);
        }
        if (unavailable) {
            return BrokerProduceOutcome.unavailable(ForwardingFailureCode.RECEIVER_UNAVAILABLE);
        }
        String t = resolver.resolveTopic(eventType, suffix);
        List<QueueEntry> q = queues.computeIfAbsent(t, k -> new ArrayList<>());
        int index = q.size();
        q.add(new QueueEntry(sequence.incrementAndGet(), message));
        locations.put(locationKey(message.headers().tenantId(), message.headers().messageId()),
                new Location(t, index));
        return BrokerProduceOutcome.accepted();
    }

    /**
     * Map a claimed outbox record to the broker-agnostic {@link BrokerOutboundMessage} this broker
     * stores. §6.2②: the body is a routing descriptor only — NEVER the payload body / token stream /
     * Task state; {@code payloadRef} rides as a header (conditionally), NOT in the descriptor. P-06:
     * the control plane (traceId/idempotencyKey/routeHandle/capability/deadline) + inlinePayload ride
     * FIRST-CLASS header fields — {@code payloadRef} is the A2A data reference only, never a
     * control-descriptor token.
     *
     * @param record the claimed outbox record to map (required)
     * @return the broker-agnostic outbound message
     */
    private static BrokerOutboundMessage toOutboundMessage(ForwardingOutboxRecord record) {
        BrokerMessageHeaders headers = new BrokerMessageHeaders(
                record.tenantId(),
                record.messageId().value(),
                record.sourceServiceId(),
                record.targetServiceId(),
                record.payloadRef(),              // A2A data reference (null for CONTROL_ONLY)
                record.correlationId(),           // FEAT-013 cross-hop correlation (mirrored from envelope)
                record.eventType(),               // FEAT-013/014 event-type (mirrored from envelope)
                record.traceId(),                 // P-06: control plane first-class, not in payloadRef
                record.idempotencyKey(),
                record.routeHandle().value(),     // opaque routeHandle value (tenantScope == tenantId)
                record.capability(),
                record.deadlineMillisEpoch(),
                record.inlinePayload(),          // bounded small body (2b); passthrough, never resolved here
                record.originalCaller());        // P-06: original caller serviceId (response routing)
        return new BrokerOutboundMessage("target=" + record.targetServiceId(), headers);
    }

    /**
     * Per-consumer {@link BrokerForwardingConsumerPort} double backed by the shared
     * {@link InMemoryBroker}'s queues. {@code subscribe} accumulates the consumer's
     * filters (multi-subscription, like RocketMQ's multi-subscribe); {@code poll} scans the
     * shared queues for this consumer-group's next uncommitted message matching ANY
     * subscribed filter, picking the globally oldest for deterministic cross-topic
     * ordering. {@code consumerServiceId} is materialised into the in-flight message
     * at poll time (ownership until commit/reject).
     */
    static final class InMemoryBrokerConsumer implements BrokerForwardingConsumerPort {
        private final InMemoryBroker broker;
        private final String consumerServiceId;
        private final List<DeliveryFilter> filters = new ArrayList<>();   // accumulated subscriptions

        InMemoryBrokerConsumer(InMemoryBroker broker, String consumerServiceId) {
            this.broker = Objects.requireNonNull(broker, "broker is required");
            requireNonBlank(consumerServiceId, "consumerServiceId");
            this.consumerServiceId = consumerServiceId;
        }

        @Override
        public void subscribe(String consumerServiceId, AgentBusEventType eventType, DeliveryFilter filter) {
            // the consumer is bound to its consumerServiceId at construction (consumerFor); the param is
            // the same group the real adapter would set — accept + accumulate the filter. The eventType
            // is resolved to a topic by the real adapter; this double scans every topic, so the eventType
            // is informational here.
            Objects.requireNonNull(consumerServiceId, "consumerServiceId is required");
            Objects.requireNonNull(eventType, "eventType is required");
            Objects.requireNonNull(filter, "filter is required");
            if (!this.consumerServiceId.equals(consumerServiceId)) {
                throw new IllegalArgumentException(
                        "consumerServiceId '" + consumerServiceId + "' does not match this consumer ('"
                                + this.consumerServiceId + "')");
            }
            filters.add(filter);
        }

        @Override
        public synchronized Optional<BrokerInboundMessage> poll(long nowMillisEpoch) {
            if (filters.isEmpty()) {
                throw new IllegalStateException(
                        "consumer '" + consumerServiceId + "' polled before subscribe");
            }
            QueueEntry picked = null;
            // Scan every topic for this consumer-group's next uncommitted message whose header properties
            // match ANY subscribed filter; pick the globally oldest (sequence) for deterministic ordering.
            for (Map.Entry<String, List<QueueEntry>> e : broker.queues.entrySet()) {
                List<QueueEntry> q = e.getValue();
                int from = broker.offsets.getOrDefault(consumerGroupKey(consumerServiceId, e.getKey()), 0);
                for (int i = from; i < q.size(); i++) {
                    BrokerMessageHeaders h = q.get(i).message.headers();
                    if (!matchesAnyFilter(h)) {
                        continue; // not targeted at this consumer — never returned
                    }
                    if (picked == null || q.get(i).sequence < picked.sequence) {
                        picked = q.get(i);
                    }
                    break; // first uncommitted matching in this queue is the queue's candidate (sequence monotonic)
                }
            }
            if (picked == null) {
                return Optional.empty();
            }
            BrokerMessageHeaders h = picked.message.headers();
            // consumerServiceId is materialised into the in-flight message at poll time
            // (ownership until commit/reject). P-06: control plane + inlinePayload mirrored headers→inbound
            // as first-class fields — the receiver reconstructs control directly, no descriptor decode.
            return Optional.of(new BrokerInboundMessage(
                    h.tenantId(),
                    h.messageId(),
                    h.sourceServiceId(),
                    h.targetServiceId(),
                    consumerServiceId,
                    h.payloadRef(),
                    h.correlationId(),               // FEAT-013 cross-hop correlation (mirrored from headers)
                    h.eventType(),                   // FEAT-013/014 event-type (mirrored from headers)
                    h.traceId(),                     // P-06: control plane first-class
                    h.idempotencyKey(),
                    h.routeHandle(),
                    h.capability(),
                    h.deadlineMillisEpoch(),
                    h.inlinePayload(),             // bounded small body (2b); passthrough
                    h.originalCaller()));          // P-06: original caller serviceId (response routing)
        }

        @Override
        public synchronized void commit(BrokerInboundMessage message) {
            Objects.requireNonNull(message, "message is required");
            Location loc = broker.locations.get(locationKey(message.tenantId(), message.messageId()));
            if (loc == null) {
                return; // unknown / already purged — nothing to advance
            }
            String key = consumerGroupKey(message.consumerServiceId(), loc.topic());
            int advancedTo = loc.index() + 1;
            int current = broker.offsets.getOrDefault(key, 0);
            if (advancedTo > current) {
                broker.offsets.put(key, advancedTo);
            }
        }

        @Override
        public synchronized void reject(BrokerInboundMessage message, ForwardingFailureCode code) {
            Objects.requireNonNull(message, "message is required");
            Objects.requireNonNull(code, "code is required");
            // NOT committed → redelivered (at-least-once). The code is recorded for observability only.
            broker.rejections.put(message.messageId(), code);
        }

        @Override
        public void close() {
            // in-memory: no broker-side consumer to shut down; idempotent no-op.
        }

        @Override
        public boolean supportsBrokerSidePropertyFilter() {
            // the in-memory "broker" filters by the subscribed properties itself — the broker-side
            // equivalent (D8). A client-side-only fallback adapter (Kafka/Redis) would return false.
            return true;
        }

        /**
         * True if the message's header properties satisfy at least one accumulated filter (D3 matching).
         *
         * @param h the message headers to test against the accumulated filters
         * @return true if at least one subscribed filter accepts the message
         */
        private boolean matchesAnyFilter(BrokerMessageHeaders h) {
            for (DeliveryFilter f : filters) {
                if (matches(h, f)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean matches(BrokerMessageHeaders h, DeliveryFilter f) {
            for (Map.Entry<String, String> criterion : f.requiredProperties().entrySet()) {
                String expected = criterion.getValue();
                String actual = headerProperty(h, criterion.getKey());
                if (actual == null || !actual.equals(expected)) {
                    return false; // a missing/non-matching property → filter excludes the message
                }
            }
            return true;
        }

        /**
         * Map a DeliveryFilter property key to the message's header value (the known routing headers).
         *
         * @param h   the message headers to read from
         * @param key the DeliveryFilter property key
         * @return the header value for the key, or {@code null} if the key is unknown
         */
        private static String headerProperty(BrokerMessageHeaders h, String key) {
            return switch (key) {
                case "tenantId" -> h.tenantId();
                case "targetServiceId" -> h.targetServiceId();
                case "sourceServiceId" -> h.sourceServiceId();
                case "messageId" -> h.messageId();
                case "correlationId" -> h.correlationId();
                default -> null; // unknown property key — the in-memory double carries only routing headers
            };
        }
    }

    private static String consumerGroupKey(String consumerServiceId, String topic) {
        return consumerServiceId + "@" + topic;
    }

    private static String locationKey(String tenantId, String messageId) {
        return tenantId + "|" + messageId;
    }

    private static String requireSuffix(String suffix) {
        Objects.requireNonNull(suffix, "suffix is required");
        if (suffix.isBlank()) {
            throw new IllegalArgumentException("suffix must not be blank");
        }
        return suffix;
    }

    private static void requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name + " is required");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
